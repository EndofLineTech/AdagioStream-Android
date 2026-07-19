package com.adagiostream.android.service.download

import com.adagiostream.android.service.audiobookshelf.AbsChapter
import com.adagiostream.android.service.library.db.AudiobookDownloadDao
import com.adagiostream.android.service.library.db.AudiobookDownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import kotlinx.coroutines.CancellationException

/**
 * Audiobook download state-machine driver (beads_adagio-59p.1.6) — the book
 * counterpart of [TrackDownloader].
 *
 * Network is behind two injected seams ([fetchPlan]/[fetchFile], plus the
 * optional [fetchCover]) so the full lifecycle — manifest creation, per-file
 * download, file-granular resume, failure — is unit tested with fakes; only
 * the OkHttp/WorkManager wiring in [AudiobookDownloadWorker] is device-only.
 *
 * Resume contract (file granularity, matching iOS): the manifest is persisted
 * BEFORE any bytes move, each file's `localPath` is written as it completes,
 * and a re-run skips files already present on disk. A partially-written file
 * is deleted on failure so the retry re-fetches it whole.
 */
class AudiobookDownloader(
    private val dao: AudiobookDownloadDao,
    private val files: DownloadFileStore,
    /** Opens a playback session + expanded item and joins them into a [Plan]. */
    private val fetchPlan: suspend (libraryItemId: String) -> Plan,
    /** Streams `GET /api/items/{id}/file/{ino}/download` to [DownloadFileStore] path. */
    private val fetchFile: suspend (file: AudiobookDownloadFile, destPath: String) -> Unit,
    /** Caches the cover image to a local path; null skips cover caching. */
    private val fetchCover: (suspend (destPath: String) -> Unit)? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Everything the manifest needs from the server, captured at download time:
     * session `audioTracks[]` (startOffset/duration) joined with the expanded
     * item's `audioFiles[]` (ino) by index.
     */
    data class Plan(
        val title: String,
        val author: String?,
        val duration: Double?,
        val files: List<AudiobookDownloadFile>,
        val chapters: List<AbsChapter>,
    )

    sealed interface Outcome {
        data object Complete : Outcome
        data class Failed(val cause: Throwable) : Outcome
    }

    /**
     * Downloads (or resumes) the book [libraryItemId]. Idempotent: a complete
     * book with all files on disk returns [Outcome.Complete] without touching
     * the network; an existing manifest is reused so a re-enqueue never
     * re-fetches the plan or files already present.
     */
    suspend fun download(libraryItemId: String, accountId: String): Outcome {
        val existing = dao.getById(libraryItemId)

        // Reuse the persisted manifest (resume); fetch the plan only once.
        var record = if (existing != null && existing.manifestFiles().isNotEmpty()) {
            existing.copy(status = DownloadStatus.DOWNLOADING, error = null, updatedAt = now())
        } else {
            val plan = try {
                fetchPlan(libraryItemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(existing, libraryItemId, accountId, e)
            }
            if (plan.files.isEmpty()) {
                return fail(existing, libraryItemId, accountId, IllegalStateException("book has no audio files"))
            }
            AudiobookDownloadEntity(
                id = libraryItemId,
                accountId = accountId,
                title = plan.title,
                author = plan.author,
                coverPath = existing?.coverPath,
                duration = plan.duration,
                status = DownloadStatus.DOWNLOADING,
                filesJson = encodeManifestFiles(plan.files),
                chaptersJson = encodeManifestChapters(plan.chapters),
                currentTime = existing?.currentTime ?: 0.0,
                createdAt = existing?.createdAt ?: now(),
                updatedAt = now(),
            )
        }
        dao.upsert(record)

        // Cover — best-effort, never fails the book.
        val coverPath = record.coverPath
        if (fetchCover != null && (coverPath == null || !files.exists(coverPath))) {
            val coverDest = files.fileFor("abs_${libraryItemId}_cover", "webp")
            try {
                fetchCover.invoke(coverDest)
                record = record.copy(coverPath = coverDest, updatedAt = now())
                dao.upsert(record)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Cover is cosmetic.
            }
        }

        // Per-file download, skipping files already present (file-granular resume).
        var manifest = record.manifestFiles()
        for ((i, file) in manifest.withIndex()) {
            val presentPath = file.localPath
            if (presentPath != null && files.exists(presentPath) && files.sizeOf(presentPath) > 0) continue

            val dest = files.fileFor("abs_${libraryItemId}_${file.ino}", file.ext ?: "audio")
            try {
                files.delete(dest) // partial from a failed attempt — re-fetch whole
                fetchFile(file, dest)
            } catch (e: CancellationException) {
                files.delete(dest)
                throw e
            } catch (e: Exception) {
                files.delete(dest)
                return fail(record, libraryItemId, accountId, e)
            }
            manifest = manifest.toMutableList().also { it[i] = file.copy(localPath = dest) }
            record = record.copy(filesJson = encodeManifestFiles(manifest), updatedAt = now())
            dao.upsert(record) // per-file persist → process death resumes here
        }

        dao.upsert(record.copy(status = DownloadStatus.COMPLETED, error = null, updatedAt = now()))
        return Outcome.Complete
    }

    private suspend fun fail(
        existing: AudiobookDownloadEntity?,
        libraryItemId: String,
        accountId: String,
        cause: Throwable,
    ): Outcome.Failed {
        dao.upsert(
            (existing ?: AudiobookDownloadEntity(
                id = libraryItemId,
                accountId = accountId,
                title = libraryItemId,
                status = DownloadStatus.FAILED,
                createdAt = now(),
                updatedAt = now(),
            )).copy(
                status = DownloadStatus.FAILED,
                error = cause.message ?: cause::class.simpleName,
                updatedAt = now(),
            ),
        )
        return Outcome.Failed(cause)
    }
}

/**
 * Deletes a downloaded book's files + cover + manifest row (files FIRST, then
 * the row — same crash-ordering rationale as [DownloadManager.delete]).
 * Shared by [AudiobookDownloadManager.delete] and tests.
 */
suspend fun removeAudiobookDownload(
    libraryItemId: String,
    dao: AudiobookDownloadDao,
    files: DownloadFileStore,
) {
    val row = dao.getById(libraryItemId) ?: return
    row.manifestFiles().forEach { f -> f.localPath?.let { files.delete(it) } }
    row.coverPath?.let { files.delete(it) }
    dao.deleteById(libraryItemId)
}
