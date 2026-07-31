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
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.util.DebugLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

/**
 * Queue-draining download worker (baw.6.1, rearchitected in beads_adagio-acq).
 *
 * ONE instance drains every `queued` row in the downloads table sequentially,
 * holding a single foreground-service notification for the whole batch. The
 * previous worker-per-track design broke the notification the moment the app
 * left the foreground: when the first workers finished, WorkManager's foreground
 * service stopped, and each later worker's `setForeground` threw
 * ForegroundServiceStartNotAllowedException (FGS start from background,
 * Android 12+) — silently degrading Download All to invisible background jobs.
 * The drainer calls `setForeground` once, while the enqueueing tap still has
 * the app foregrounded, and the notification survives until the queue is empty.
 *
 * The downloads table is shared with Audiobookshelf books/episodes (their own
 * workers): rows without a cached Navidrome track are skipped, not failed.
 *
 * Cancellation contract: deleting a row (DownloadManager.delete) cancels that
 * track — mid-transfer, the drainer notices the missing row at the next
 * progress checkpoint, aborts the transfer, and removes the re-created partial
 * file. WorkManager survives process death and re-runs the drainer, which
 * re-queues stale DOWNLOADING rows and resumes from on-disk offsets.
 * Credentials are read from [PersistenceService] (disk) so a process-death
 * resume still has auth.
 *
 * ponytail: tracks download sequentially (the old design ran N workers in
 * parallel) — the deliberate trade for one coherent foreground notification;
 * revisit with a multi-download progress notification if throughput complaints
 * show up.
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
        fun libraryRepository(): MusicLibraryRepository
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_ATTEMPTS = 5
        private const val PROGRESS_UPDATE_BYTES = 1_048_576L // ~1 MB
    }

    /** Thrown from the progress callback when the track's row was deleted mid-transfer. */
    private class CancelledMidTransfer : RuntimeException()

    override suspend fun doWork(): Result {
        // Legacy per-track requests (pre-drainer, named "download:<id>") can
        // survive an app update in WorkManager's queue. Running them as extra
        // drainers would race this one on the same rows/files — no-op them;
        // their rows drain on the next real kick (beads_adagio-acq review F3).
        if (inputData.getString("trackId") != null) return Result.success()

        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadWorkerEntryPoint::class.java,
        )
        val dao = deps.downloadDao()
        val files = deps.fileStore()
        val repo = deps.libraryRepository()

        // Resolve the active Subsonic account from disk (survives process death).
        val subsonic = deps.persistenceService().loadAccounts()
            .filter { it.isEnabled }
            .firstNotNullOfOrNull { it.type as? AccountType.Subsonic }
            ?: return Result.failure()
        val api = deps.navidromeApiFactory().create(subsonic.host, subsonic.username, subsonic.password)

        try {
            setForeground(foregroundInfo(progressText = "Starting…"))
        } catch (_: Exception) {
            // Only reachable when WorkManager (re)starts the drainer while the
            // app is backgrounded (e.g. a retry after network loss) — the batch
            // still downloads, just without the notification.
        }

        run {
            val byStatus = dao.getAll().groupingBy { it.status }.eachCount()
            DebugLogger.log("Drainer start (attempt=$runAttemptCount): rows by status = $byStatus", DebugLogger.Category.DOWNLOAD)
        }

        // Re-queue rows a previous run left mid-transfer (process death, network
        // loss, reboot) — only one drainer chain ever runs, so any DOWNLOADING
        // row at start is stale and would otherwise be stuck in the UI forever
        // (beads_adagio-acq review F1). Filtered to cached Navidrome tracks —
        // ABS rows belong to other workers.
        dao.getByStatus(DownloadStatus.DOWNLOADING).forEach { row ->
            if (resolveTrack(row.id, repo, api) != null) {
                DebugLogger.log("Re-queueing stale DOWNLOADING row ${row.id}", DebugLogger.Category.DOWNLOAD)
                dao.updateStatus(row.id, DownloadStatus.QUEUED, System.currentTimeMillis())
            } else {
                DebugLogger.log("NOT re-queueing DOWNLOADING row ${row.id} — not a music track", DebugLogger.Category.DOWNLOAD)
            }
        }

        // Re-queue FAILED tracks unconditionally — a kick only happens when
        // something is pending, so this gives failed rows one fresh attempt per
        // drain pass (bounded by MAX_ATTEMPTS within a chain) and heals rows
        // the pre-drainer build's workers left FAILED with no retry scheduled.
        dao.getByStatus(DownloadStatus.FAILED).forEach { row ->
            if (resolveTrack(row.id, repo, api) != null) {
                DebugLogger.log("Re-queueing FAILED row ${row.id}", DebugLogger.Category.DOWNLOAD)
                dao.updateStatus(row.id, DownloadStatus.QUEUED, System.currentTimeMillis())
            }
        }

        val skipped = mutableSetOf<String>()
        var completed = 0
        var anyFailed = false

        while (true) {
            val queued = dao.getByStatus(DownloadStatus.QUEUED)
                .filterNot { it.id in skipped }
                .sortedBy { it.createdAt }
            val next = queued.firstOrNull() ?: break
            val trackId = next.id

            val track = resolveTrack(trackId, repo, api)
            if (track == null) {
                // Not a Navidrome track (ABS book/episode row) — leave for its own worker.
                DebugLogger.log("Skipping row $trackId — not a music track", DebugLogger.Category.DOWNLOAD)
                skipped += trackId
                continue
            }

            val url = api.downloadUrl(trackId)?.toString()
            if (url == null) {
                DebugLogger.log("No download URL for $trackId — marking failed", DebugLogger.Category.DOWNLOAD)
                dao.updateStatus(trackId, DownloadStatus.FAILED, System.currentTimeMillis())
                anyFailed = true
                continue
            }

            val position = completed + 1
            val batchTotal = completed + queued.size
            val label = "${track.title} ($position of $batchTotal)"
            runCatching { setForeground(foregroundInfo(label)) }

            var lastCheckAt = 0L
            val downloader = TrackDownloader(
                dao = dao,
                source = OkHttpByteRangeDownloader(deps.okHttpClient()),
                files = files,
                onBytes = { id, written, total ->
                    if (written - lastCheckAt >= PROGRESS_UPDATE_BYTES) {
                        lastCheckAt = written
                        if (dao.getById(id) == null) throw CancelledMidTransfer()
                        runCatching { setForeground(foregroundInfo("$label · ${progressLine(written, total)}")) }
                    }
                },
            )

            try {
                when (val outcome = downloader.download(trackId, url, track.suffix)) {
                    is DownloadOutcome.Completed -> {
                        // A file smaller than the declared total means it was
                        // deleted underneath us (user cancel between ~1MB
                        // checkpoints) and rebuilt from tail appends — drop it
                        // rather than surface a corrupt "completed" download
                        // (beads_adagio-acq review F2). The narrower IOException
                        // variant of this race resurrects a FAILED row instead,
                        // which at worst re-downloads once on a retry pass.
                        if (outcome.totalBytes != null && outcome.bytesWritten < outcome.totalBytes) {
                            files.delete(outcome.localPath)
                            dao.deleteById(trackId)
                        } else {
                            completed++
                        }
                    }
                    is DownloadOutcome.AlreadyComplete -> {
                        DebugLogger.log("Already complete: ${track.title}", DebugLogger.Category.DOWNLOAD)
                        completed++
                    }
                    is DownloadOutcome.Failed -> {
                        DebugLogger.log("Failed: ${track.title} — ${outcome.cause.message}", DebugLogger.Category.DOWNLOAD)
                        anyFailed = true
                    }
                }
            } catch (e: CancelledMidTransfer) {
                // Row deleted mid-transfer — remove the partial file the
                // in-flight append re-created after DownloadManager.delete.
                files.delete(next.localPath ?: files.fileFor(trackId, track.suffix))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // TrackDownloader only maps IOException to FAILED itself; make
                // sure an unexpected error can't strand the row in DOWNLOADING
                // (which would loop forever as it never returns to QUEUED).
                dao.updateStatus(trackId, DownloadStatus.FAILED, System.currentTimeMillis())
                anyFailed = true
            }
        }

        val result = when {
            !anyFailed -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
        DebugLogger.log("Drainer done: completed=$completed skipped=${skipped.size} anyFailed=$anyFailed result=$result", DebugLogger.Category.DOWNLOAD)
        return result
    }

    /**
     * Resolves track metadata for a download row: the library cache first, then
     * a `getSong` refetch that re-populates the cache (beads_adagio-6q3 — the
     * pre-@Upsert REPLACE+CASCADE bug orphaned download rows by wiping their
     * cached tracks). A fetch failure means the row isn't a Navidrome song
     * (ABS book/episode sharing the downloads table) or the server is
     * unreachable — either way the caller skips it this pass.
     */
    private suspend fun resolveTrack(
        trackId: String,
        repo: MusicLibraryRepository,
        api: com.adagiostream.android.service.navidrome.NavidromeApi,
    ): com.adagiostream.android.service.navidrome.Track? {
        repo.cachedTrack(trackId)?.let { return it }
        return try {
            val fetched = api.getSong(trackId)
            repo.cacheTrackForDownload(fetched)
            DebugLogger.log("Recovered track metadata via getSong for $trackId", DebugLogger.Category.DOWNLOAD)
            fetched
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Distinguishes "Subsonic error 70: not found" (ABS row) from a
            // network failure in the exported log — this ticket was diagnosed
            // entirely from these lines.
            DebugLogger.log("getSong failed for $trackId: ${e.message}", DebugLogger.Category.DOWNLOAD)
            null
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
