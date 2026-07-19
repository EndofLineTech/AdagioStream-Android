package com.adagiostream.android.service.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.adagiostream.android.R
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import com.adagiostream.android.service.library.db.AudiobookDownloadDao
import com.adagiostream.android.service.persistence.PersistenceService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Background audiobook download worker (beads_adagio-59p.1.6) — the book
 * counterpart of [DownloadWorker], one instance per BOOK.
 *
 * WorkManager survives process death; on re-run the persisted manifest lets
 * [AudiobookDownloader] skip files already on disk (file-granular resume).
 * Auth goes through [AudiobookshelfAuth.execute] (Bearer header, 401 →
 * refresh → retry) and rotated JWTs are persisted back to the encrypted
 * account store, so a queued download never strands on token rotation.
 */
class AudiobookDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AudiobookDownloadWorkerEntryPoint {
        fun persistenceService(): PersistenceService
        fun audiobookshelfApiFactory(): AudiobookshelfApiFactory
        fun okHttpClient(): OkHttpClient
        fun audiobookDownloadDao(): AudiobookDownloadDao
        fun fileStore(): DownloadFileStore
        fun accountManager(): dagger.Lazy<AccountManager>
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4202
        private const val MAX_ATTEMPTS = 5
        private const val BUFFER_SIZE = 64 * 1024
    }

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(AudiobookDownloadManager.KEY_ITEM_ID) ?: return Result.failure()
        val accountId = inputData.getString(AudiobookDownloadManager.KEY_ACCOUNT_ID) ?: return Result.failure()

        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            AudiobookDownloadWorkerEntryPoint::class.java,
        )

        // Resolve the ABS account from disk (survives process death).
        val account = deps.persistenceService().loadAccounts().firstOrNull { it.id == accountId }
            ?: return Result.failure()
        val abs = account.type as? AccountType.Audiobookshelf ?: return Result.failure()

        val tokens = abs.accessToken?.let { at ->
            abs.refreshToken?.let { rt -> AudiobookshelfAuth.Tokens(at, rt) }
        }
        val tokenPersistScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        val api = deps.audiobookshelfApiFactory().create(abs.host, abs.username, null, tokens) { newTokens ->
            // Rotated JWTs must persist immediately (refresh tokens are single-use).
            tokenPersistScope.launch {
                deps.accountManager().get().updateAccountCredentials(
                    account.copy(
                        type = abs.copy(
                            accessToken = newTokens?.accessToken,
                            refreshToken = newTokens?.refreshToken,
                        ),
                    ),
                )
            }
        }

        try {
            setForeground(foregroundInfo("Starting…"))
        } catch (_: Exception) {
            // setForeground can fail when backgrounded; the download proceeds.
        }

        val files = deps.fileStore()
        var fileCount = 0
        var fileDone = 0
        val downloader = AudiobookDownloader(
            dao = deps.audiobookDownloadDao(),
            files = files,
            fetchPlan = { id ->
                fetchPlan(api, id, deps.persistenceService().deviceId()).also { fileCount = it.files.size }
            },
            fetchFile = { file, dest ->
                fileDone++
                runCatching { setForeground(foregroundInfo("File $fileDone of ${maxOf(fileCount, fileDone)}")) }
                streamToStore(api, api.fileDownloadUrl(itemId, file.ino)?.toString(), dest, files)
            },
            fetchCover = { dest ->
                val coverUrl = api.coverUrl(itemId, width = 800)?.toString()
                    ?: throw IOException("no cover url")
                streamPlain(deps.okHttpClient(), coverUrl, dest, files)
            },
        )

        return when (downloader.download(itemId, accountId)) {
            is AudiobookDownloader.Outcome.Complete -> Result.success()
            is AudiobookDownloader.Outcome.Failed ->
                if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /**
     * Opens a playback session (files + global offsets, mirror of iOS: the
     * manifest comes from the session at download time), joins `audioTracks[]`
     * with the expanded item's `audioFiles[]` by index for the download `ino`,
     * then closes the session — it was opened purely for enumeration.
     */
    private suspend fun fetchPlan(
        api: AudiobookshelfApi,
        itemId: String,
        deviceId: String,
    ): AudiobookDownloader.Plan {
        val session = api.openPlaybackSession(itemId, deviceId = deviceId)
        session.id.takeIf { it.isNotEmpty() }?.let { api.closeSession(it) }
        val item = api.getItem(itemId)
        val inoByIndex = item.media?.audioFiles.orEmpty().associateBy({ it.index }, { it.ino })
        val files = session.audioTracks.orEmpty().mapNotNull { track ->
            val ino = inoByIndex[track.index]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AudiobookDownloadFile(
                index = track.index,
                ino = ino,
                startOffset = track.startOffset,
                duration = track.duration,
                ext = track.contentUrl.substringAfterLast('.', "").takeIf { it.length in 1..5 },
            )
        }
        return AudiobookDownloader.Plan(
            title = session.displayTitle ?: session.mediaMetadata?.title ?: "Audiobook",
            author = session.mediaMetadata?.displayAuthor,
            duration = session.duration,
            files = files,
            chapters = session.chapters.orEmpty(),
        )
    }

    /** Streams an authed (Bearer, 401-refresh) GET to the file store. */
    private suspend fun streamToStore(
        api: AudiobookshelfApi,
        url: String?,
        dest: String,
        files: DownloadFileStore,
    ) {
        if (url == null) throw IOException("unresolvable download url")
        val response = api.auth.execute(Request.Builder().url(url).get().build())
        response.use { resp ->
            if (resp.code !in 200..299) throw IOException("HTTP ${resp.code}")
            copyBody(resp.body.byteStream(), dest, files)
        }
    }

    /** Streams a plain GET (token-in-query cover URL) to the file store. */
    private fun streamPlain(client: OkHttpClient, url: String, dest: String, files: DownloadFileStore) {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.code !in 200..299) throw IOException("HTTP ${resp.code}")
            copyBody(resp.body.byteStream(), dest, files)
        }
    }

    private fun copyBody(input: java.io.InputStream, dest: String, files: DownloadFileStore) {
        files.delete(dest)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) files.append(dest, buffer, read)
        }
    }

    private fun foregroundInfo(progressText: String): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading audiobook")
            .setContentText(progressText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Downloads",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }
}
