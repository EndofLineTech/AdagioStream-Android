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
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.persistence.PersistenceService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

/**
 * Background download worker (baw.6.1).
 *
 * WorkManager survives process death and re-runs queued/retrying work, so the
 * download resumes from the on-disk offset on a fresh process. Dependencies are
 * resolved via a Hilt [DownloadWorkerEntryPoint] (no hilt-work / custom
 * WorkerFactory needed). Account credentials are read from [PersistenceService]
 * (disk) rather than an in-memory flow, so a process-death resume still has auth.
 *
 * Runs as a `dataSync` foreground service while downloading. Real foreground-service
 * rendering and OkHttp byte transfer are device-only; the transfer/resume/state
 * logic lives in the unit-tested [TrackDownloader].
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadWorkerEntryPoint {
        fun persistenceService(): PersistenceService
        fun navidromeApiFactory(): NavidromeApiFactory
        fun okHttpClient(): OkHttpClient
        fun downloadDao(): DownloadDao
        fun fileStore(): DownloadFileStore
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_ATTEMPTS = 5
        private const val PROGRESS_UPDATE_BYTES = 1_048_576L // ~1 MB
    }

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(DownloadManager.KEY_TRACK_ID) ?: return Result.failure()
        val suffix = inputData.getString(DownloadManager.KEY_SUFFIX)

        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadWorkerEntryPoint::class.java,
        )

        // Resolve the active Subsonic account from disk (survives process death).
        val subsonic = deps.persistenceService().loadAccounts()
            .filter { it.isEnabled }
            .firstNotNullOfOrNull { it.type as? AccountType.Subsonic }
            ?: return Result.failure()

        val api = deps.navidromeApiFactory().create(subsonic.host, subsonic.username, subsonic.password)
        val url = api.downloadUrl(trackId)?.toString() ?: return Result.failure()

        try {
            setForeground(foregroundInfo(progressText = "Starting…"))
        } catch (_: Exception) {
            // setForeground can fail if the app is backgrounded without the right
            // window; the download still proceeds.
        }

        var lastNotifiedAt = 0L
        val downloader = TrackDownloader(
            dao = deps.downloadDao(),
            source = OkHttpByteRangeDownloader(deps.okHttpClient()),
            files = deps.fileStore(),
            onBytes = { _, written, total ->
                if (written - lastNotifiedAt >= PROGRESS_UPDATE_BYTES) {
                    lastNotifiedAt = written
                    runCatching { setForeground(foregroundInfo(progressLine(written, total))) }
                }
            },
        )

        return when (downloader.download(trackId, url, suffix)) {
            is DownloadOutcome.Completed, is DownloadOutcome.AlreadyComplete -> Result.success()
            is DownloadOutcome.Failed ->
                if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun progressLine(written: Long, total: Long?): String =
        if (total != null && total > 0) {
            "${StorageAccounting.formatSize(written)} / ${StorageAccounting.formatSize(total)}"
        } else {
            StorageAccounting.formatSize(written)
        }

    private fun foregroundInfo(progressText: String): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading music")
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
