package com.adagiostream.android.service.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.DownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.navidrome.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point for offline downloads (baw.6.1).
 *
 * Enqueues a [DownloadWorker] per track (unique work keyed by the **track id**,
 * never the auth URL), tracks state in the `downloads` table, and owns the
 * delete/cancel ordering (file THEN row). Bulk enqueue backs the album/playlist
 * "Download All" actions.
 *
 * The worker resolves the authenticated `download.view` URL itself at run time, so
 * a rotated auth token never strands queued work.
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
        const val KEY_TRACK_ID = "trackId"
        const val KEY_SUFFIX = "suffix"
        private const val WORK_PREFIX = "download:"

        /** Unique work name derived from the track id (stable across auth rotation). */
        fun workName(trackId: String): String = "$WORK_PREFIX$trackId"
    }

    /**
     * Queues [track] for download. Caches the track for offline browse, writes the
     * `queued` row, and enqueues unique work (KEEP — a second tap won't duplicate
     * an in-flight download).
     */
    suspend fun enqueue(track: Track) {
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
        workManager.enqueueUniqueWork(
            workName(track.id),
            ExistingWorkPolicy.KEEP,
            buildRequest(track),
        )
    }

    /** Bulk enqueue for album/playlist "Download All". */
    suspend fun enqueueAll(tracks: List<Track>) {
        tracks.forEach { enqueue(it) }
    }

    /**
     * Retries a failed/paused download — REPLACE so a fresh worker resumes from the
     * stored offset.
     */
    suspend fun retry(track: Track) {
        downloadDao.updateStatus(track.id, DownloadStatus.QUEUED, now())
        workManager.enqueueUniqueWork(
            workName(track.id),
            ExistingWorkPolicy.REPLACE,
            buildRequest(track),
        )
    }

    /** Cancels in-flight work and removes the partial file + row (file THEN row). */
    suspend fun cancel(trackId: String) = delete(trackId)

    /**
     * Deletes a download: cancels work, removes the file FIRST, then the row.
     *
     * Ordering matters — deleting the row first then crashing would orphan the file
     * with nothing pointing at it. File-then-row leaves at worst a row whose file is
     * already gone, which the next access cleans up.
     */
    suspend fun delete(trackId: String) {
        workManager.cancelUniqueWork(workName(trackId))
        val row = downloadDao.getById(trackId)
        row?.localPath?.let { fileStore.delete(it) }
        downloadDao.deleteById(trackId)
    }

    /** Deletes every download (storage screen "Delete All"). */
    suspend fun deleteAll() {
        downloadDao.getAll().forEach { row ->
            workManager.cancelUniqueWork(workName(row.id))
            row.localPath?.let { fileStore.delete(it) }
        }
        downloadDao.deleteAll()
    }

    private fun buildRequest(track: Track) =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    KEY_TRACK_ID to track.id,
                    KEY_SUFFIX to track.suffix,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
}
