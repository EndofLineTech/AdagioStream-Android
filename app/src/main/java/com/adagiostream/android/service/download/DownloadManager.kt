package com.adagiostream.android.service.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.DownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.util.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point for offline downloads (baw.6.1, beads_adagio-acq).
 *
 * Writes `queued` rows to the `downloads` table and kicks ONE unique
 * queue-draining [DownloadWorker] (`APPEND_OR_REPLACE` — a running drainer
 * gets a follow-up pass appended, so a row enqueued in the race window where
 * the drainer just saw an empty queue is never stranded). The drainer holds a
 * single foreground notification for the whole batch; a worker per track lost
 * the notification once the app was backgrounded.
 *
 * Owns the delete/cancel ordering (file THEN row). Cancelling in-flight work is
 * row-driven: the drainer aborts the current transfer when it notices the row
 * is gone. The worker resolves the authenticated `download.view` URL itself at
 * run time, so a rotated auth token never strands queued work.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val libraryRepository: MusicLibraryRepository,
    private val fileStore: DownloadFileStore,
) {

    private val now: () -> Long = { System.currentTimeMillis() }
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    companion object {
        /** Single unique work name for the queue-draining worker (beads_adagio-acq). */
        const val QUEUE_WORK_NAME = "downloads-queue"
    }

    /**
     * Queues [track] for download. Caches the track for offline browse, writes the
     * `queued` row, and kicks the drainer. A second tap just rewrites the row —
     * the drainer is idempotent.
     */
    suspend fun enqueue(track: Track) {
        upsertQueuedRow(track)
        kickWorker()
    }

    /** Bulk enqueue for album/playlist "Download All" — one drainer kick for the batch. */
    suspend fun enqueueAll(tracks: List<Track>) {
        tracks.forEach { upsertQueuedRow(it) }
        kickWorker()
    }

    /** Retries a failed/paused download — the drainer resumes from the stored offset. */
    suspend fun retry(track: Track) {
        downloadDao.updateStatus(track.id, DownloadStatus.QUEUED, now())
        kickWorker()
    }

    private suspend fun upsertQueuedRow(track: Track) {
        libraryRepository.cacheTrackForDownload(track)
        val existing = downloadDao.getById(track.id)
        downloadDao.upsert(
            DownloadEntity(
                id = track.id,
                status = DownloadStatus.QUEUED,
                localPath = existing?.localPath,
                resumeOffset = existing?.resumeOffset ?: 0,
                bytesTotal = existing?.bytesTotal ?: 0,
                createdAt = existing?.createdAt ?: now(),
                updatedAt = now(),
            ),
        )
    }

    private fun kickWorker() {
        workManager.enqueueUniqueWork(
            QUEUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildRequest(),
        )
    }

    /**
     * Kicks the drainer if any download rows are still pending (beads_adagio-0wj).
     *
     * Called once at app startup. Heals rows stranded when nothing else will
     * kick the drainer: legacy per-track works no-op'd across the
     * beads_adagio-acq update, or a process killed before WorkManager's retry
     * fired. Rows that aren't music tracks are skipped by the drainer itself,
     * so a spurious kick is a fast no-op pass.
     */
    suspend fun resumePendingOnStartup() {
        // FAILED counts as pending: rows the pre-drainer build's workers left
        // FAILED have no retry scheduled anywhere — the drainer re-queues them
        // for one fresh attempt per kick.
        val pending = downloadDao.getByStatus(DownloadStatus.QUEUED) +
            downloadDao.getByStatus(DownloadStatus.DOWNLOADING) +
            downloadDao.getByStatus(DownloadStatus.FAILED)
        DebugLogger.log("Startup: ${pending.size} pending download rows", DebugLogger.Category.DOWNLOAD)
        if (pending.isNotEmpty()) kickWorker()
    }

    /** Cancels an in-flight/queued download and removes the partial file + row (file THEN row). */
    suspend fun cancel(trackId: String) = delete(trackId)

    /**
     * Deletes a download: removes the file FIRST, then the row.
     *
     * Ordering matters — deleting the row first then crashing would orphan the file
     * with nothing pointing at it. File-then-row leaves at worst a row whose file is
     * already gone, which the next access cleans up. If the drainer is mid-transfer
     * on this track it notices the missing row at its next progress checkpoint,
     * aborts, and deletes the re-created partial file.
     */
    suspend fun delete(trackId: String) {
        val row = downloadDao.getById(trackId)
        row?.localPath?.let { fileStore.delete(it) }
        downloadDao.deleteById(trackId)
    }

    /** Deletes every download (storage screen "Delete All"). */
    suspend fun deleteAll() {
        workManager.cancelUniqueWork(QUEUE_WORK_NAME)
        downloadDao.getAll().forEach { row ->
            row.localPath?.let { fileStore.delete(it) }
        }
        downloadDao.deleteAll()
    }

    private fun buildRequest() =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
}
