package com.adagiostream.android.service.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.adagiostream.android.model.Account
import com.adagiostream.android.service.audiobookshelf.OfflineAudiobook
import com.adagiostream.android.service.audiobookshelf.OfflineAudiobookSource
import com.adagiostream.android.service.library.db.AudiobookDownloadDao
import com.adagiostream.android.service.library.db.AudiobookDownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Download/delete surface for audiobook offline copies (beads_adagio-59p.1.6).
 *
 * This is the injectable seam the library-UI detail screen (parallel branch)
 * wires its download button to at integration time; the storage screen uses it
 * today. Implemented by [AudiobookDownloadManager].
 */
interface AudiobookDownloadActions {

    /** Live manifest row for one book (null = not downloaded). */
    fun observe(libraryItemId: String): Flow<AudiobookDownloadEntity?>

    /** All manifest rows, newest first — the storage screen listing. */
    fun observeAll(): Flow<List<AudiobookDownloadEntity>>

    /**
     * Queues the book for download. [title]/[author] pre-fill the manifest for
     * immediate UI feedback; the worker overwrites them from the server.
     */
    suspend fun download(account: Account, libraryItemId: String, title: String? = null, author: String? = null)

    /** Cancels any in-flight work and removes files + manifest row. */
    suspend fun delete(libraryItemId: String)
}

/**
 * Public entry point for audiobook offline downloads — the book counterpart of
 * [DownloadManager]. Enqueues one [AudiobookDownloadWorker] per BOOK (unique
 * work keyed by the library-item id; the worker iterates the book's files and
 * skips ones already on disk, so re-enqueue = file-granular resume).
 *
 * Also the [OfflineAudiobookSource] backing the playback coordinator's
 * offline path: timeline reconstruction from the manifest + position cache.
 */
@Singleton
class AudiobookDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AudiobookDownloadDao,
    private val fileStore: DownloadFileStore,
) : AudiobookDownloadActions, OfflineAudiobookSource {

    private val now: () -> Long = { System.currentTimeMillis() }
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    companion object {
        const val KEY_ITEM_ID = "libraryItemId"
        const val KEY_ACCOUNT_ID = "accountId"
        private const val WORK_PREFIX = "absdownload:"

        fun workName(libraryItemId: String): String = "$WORK_PREFIX$libraryItemId"
    }

    // ---- AudiobookDownloadActions -----------------------------------------

    override fun observe(libraryItemId: String): Flow<AudiobookDownloadEntity?> =
        dao.observeById(libraryItemId)

    override fun observeAll(): Flow<List<AudiobookDownloadEntity>> = dao.observeAll()

    override suspend fun download(account: Account, libraryItemId: String, title: String?, author: String?) {
        val existing = dao.getById(libraryItemId)
        if (existing?.status == DownloadStatus.COMPLETED && existing.allFilesPresent(fileStore)) return

        // Stub row for immediate UI feedback; the worker fills/overwrites the
        // manifest from the playback session. Preserves an existing manifest so
        // re-enqueue resumes at file granularity.
        dao.upsert(
            (existing ?: AudiobookDownloadEntity(
                id = libraryItemId,
                accountId = account.id,
                title = title ?: "Audiobook",
                author = author,
                status = DownloadStatus.QUEUED,
                createdAt = now(),
                updatedAt = now(),
            )).copy(
                accountId = account.id,
                status = DownloadStatus.QUEUED,
                error = null,
                updatedAt = now(),
            ),
        )
        workManager.enqueueUniqueWork(
            workName(libraryItemId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AudiobookDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ITEM_ID to libraryItemId,
                        KEY_ACCOUNT_ID to account.id,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true) // GB-scale books
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build(),
        )
    }

    override suspend fun delete(libraryItemId: String) {
        workManager.cancelUniqueWork(workName(libraryItemId))
        removeAudiobookDownload(libraryItemId, dao, fileStore)
    }

    // ---- OfflineAudiobookSource -------------------------------------------

    override suspend fun completeBook(libraryItemId: String): OfflineAudiobook? {
        val row = dao.getById(libraryItemId) ?: return null
        if (!row.allFilesPresent(fileStore)) return null
        return OfflineAudiobook(
            libraryItemId = row.id,
            title = row.title,
            author = row.author,
            coverPath = row.coverPath?.takeIf { fileStore.exists(it) },
            currentTime = row.currentTime,
            timeline = row.offlineTimeline(),
        )
    }

    override suspend fun savePosition(libraryItemId: String, seconds: Double) {
        dao.updateCurrentTime(libraryItemId, seconds, now())
    }

    // ---- storage accounting -----------------------------------------------

    /** On-disk size of one book (files + cover), from File.length sums. */
    fun sizeOf(row: AudiobookDownloadEntity): Long =
        row.manifestFiles().sumOf { f -> f.localPath?.let { fileStore.sizeOf(it) } ?: 0L } +
            (row.coverPath?.let { fileStore.sizeOf(it) } ?: 0L)
}
