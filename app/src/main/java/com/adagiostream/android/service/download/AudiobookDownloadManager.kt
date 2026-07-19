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

    /** Live manifest row for one episode (null = not downloaded). */
    fun observeEpisode(showLibraryItemId: String, episodeId: String): Flow<AudiobookDownloadEntity?>

    /**
     * Queues one podcast episode for download (bead .2.3). Reuses the book
     * manifest/worker — one episode = one file — under a composite record id so
     * it never collides with the show's book id or a sibling episode.
     */
    suspend fun downloadEpisode(
        account: Account,
        showLibraryItemId: String,
        episodeId: String,
        title: String? = null,
        author: String? = null,
    )

    /** Cancels any in-flight episode work and removes its files + manifest row. */
    suspend fun deleteEpisode(showLibraryItemId: String, episodeId: String)
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
        const val KEY_EPISODE_ID = "episodeId"
        private const val WORK_PREFIX = "absdownload:"

        fun workName(recordId: String): String = "$WORK_PREFIX$recordId"

        /**
         * Composite manifest/work id for a downloaded episode (bead .2.3, iOS
         * parity `AudiobookDownloadRecord.episodeRecordID`). The `episode:`
         * prefix + both ids keep it collision-free against book ids (which are
         * bare library-item ids) and sibling episodes of the same show.
         */
        fun episodeRecordId(showLibraryItemId: String, episodeId: String): String =
            "episode:$showLibraryItemId:$episodeId"
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
        enqueueWork(libraryItemId, libraryItemId, account.id, episodeId = null)
    }

    override suspend fun delete(libraryItemId: String) {
        workManager.cancelUniqueWork(workName(libraryItemId))
        removeAudiobookDownload(libraryItemId, dao, fileStore)
    }

    override fun observeEpisode(showLibraryItemId: String, episodeId: String): Flow<AudiobookDownloadEntity?> =
        dao.observeById(episodeRecordId(showLibraryItemId, episodeId))

    override suspend fun downloadEpisode(
        account: Account,
        showLibraryItemId: String,
        episodeId: String,
        title: String?,
        author: String?,
    ) {
        val recordId = episodeRecordId(showLibraryItemId, episodeId)
        val existing = dao.getById(recordId)
        if (existing?.status == DownloadStatus.COMPLETED && existing.allFilesPresent(fileStore)) return

        dao.upsert(
            (existing ?: AudiobookDownloadEntity(
                id = recordId,
                accountId = account.id,
                title = title ?: "Episode",
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
        // itemId = SHOW library-item id (the /play + /file URLs use it); the
        // manifest row + work are keyed by the composite recordId.
        enqueueWork(recordId, showLibraryItemId, account.id, episodeId = episodeId)
    }

    override suspend fun deleteEpisode(showLibraryItemId: String, episodeId: String) {
        val recordId = episodeRecordId(showLibraryItemId, episodeId)
        workManager.cancelUniqueWork(workName(recordId))
        removeAudiobookDownload(recordId, dao, fileStore)
    }

    /** Enqueues one unique download worker keyed by [recordId]. */
    private fun enqueueWork(recordId: String, itemId: String, accountId: String, episodeId: String?) {
        val data = if (episodeId != null) {
            workDataOf(KEY_ITEM_ID to itemId, KEY_ACCOUNT_ID to accountId, KEY_EPISODE_ID to episodeId)
        } else {
            workDataOf(KEY_ITEM_ID to itemId, KEY_ACCOUNT_ID to accountId)
        }
        workManager.enqueueUniqueWork(
            workName(recordId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AudiobookDownloadWorker>()
                .setInputData(data)
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

    // ---- OfflineAudiobookSource -------------------------------------------

    override suspend fun completeBook(libraryItemId: String): OfflineAudiobook? =
        offlineFrom(dao.getById(libraryItemId), libraryItemId)

    override suspend fun completeEpisode(showLibraryItemId: String, episodeId: String): OfflineAudiobook? =
        offlineFrom(dao.getById(episodeRecordId(showLibraryItemId, episodeId)), showLibraryItemId)

    /**
     * Builds an [OfflineAudiobook] from a complete manifest row. [playItemId] is
     * the id the coordinator plays under — the show's library-item id for an
     * episode (so progress keys by show+episode), the book id for a book —
     * NOT the composite record id.
     */
    private fun offlineFrom(row: AudiobookDownloadEntity?, playItemId: String): OfflineAudiobook? {
        if (row == null || !row.allFilesPresent(fileStore)) return null
        return OfflineAudiobook(
            libraryItemId = playItemId,
            title = row.title,
            author = row.author,
            coverPath = row.coverPath?.takeIf { fileStore.exists(it) },
            currentTime = row.currentTime,
            timeline = row.offlineTimeline(),
        )
    }

    override suspend fun savePosition(libraryItemId: String, episodeId: String?, seconds: Double) {
        val id = if (episodeId != null) episodeRecordId(libraryItemId, episodeId) else libraryItemId
        dao.updateCurrentTime(id, seconds, now())
    }

    // ---- storage accounting -----------------------------------------------

    /** On-disk size of one book (files + cover), from File.length sums. */
    fun sizeOf(row: AudiobookDownloadEntity): Long =
        row.manifestFiles().sumOf { f -> f.localPath?.let { fileStore.sizeOf(it) } ?: 0L } +
            (row.coverPath?.let { fileStore.sizeOf(it) } ?: 0L)
}
