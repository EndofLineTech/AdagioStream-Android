package com.adagiostream.android.service.download

import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.DownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import java.io.IOException

/**
 * The pure download state-machine driver (baw.6.1).
 *
 * Owns the queued → downloading → completed / failed transitions and byte-range
 * resume. Network, files, time and persistence are all injected seams, so the
 * full lifecycle — including "retry resumes from the stored offset" — is unit
 * tested with fakes; only the real OkHttp/file/WorkManager wiring is device-only.
 *
 * Resume contract: the offset is taken from the **actual bytes on disk**
 * ([DownloadFileStore.sizeOf]), which is also mirrored into
 * [DownloadEntity.resumeOffset]. If the server ignores the Range request (HTTP
 * 200 rather than 206) the partial file is truncated and the download restarts.
 */
class TrackDownloader(
    private val dao: DownloadDao,
    private val source: ByteRangeDownloader,
    private val files: DownloadFileStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onBytes: (suspend (trackId: String, written: Long, total: Long?) -> Unit)? = null,
) {

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }

    /**
     * Downloads [trackId] from [url], resuming from any partial file on disk.
     *
     * Idempotent: a track already [DownloadStatus.COMPLETED] with its file present
     * returns [DownloadOutcome.AlreadyComplete] without touching the network.
     */
    suspend fun download(trackId: String, url: String, suffix: String? = null): DownloadOutcome {
        val existing = dao.getById(trackId)
        val path = existing?.localPath ?: files.fileFor(trackId, suffix)

        // Idempotent short-circuit for completed downloads whose file still exists.
        if (existing?.status == DownloadStatus.COMPLETED && files.exists(path) && files.sizeOf(path) > 0) {
            return DownloadOutcome.AlreadyComplete(path, files.sizeOf(path))
        }

        var offset = files.sizeOf(path)

        // Persist the downloading state, mirroring the resume offset.
        upsertProgress(
            id = trackId,
            status = DownloadStatus.DOWNLOADING,
            resumeOffset = offset,
            bytesTotal = existing?.bytesTotal ?: 0,
            localPath = path,
            error = null,
            createdAt = existing?.createdAt ?: now(),
        )

        var total: Long? = existing?.bytesTotal?.takeIf { it > 0 }
        return try {
            val response = source.open(url, offset)
            try {
                // Server ignored the Range request — restart from byte 0.
                if (offset > 0 && !response.partial) {
                    files.truncate(path)
                    offset = 0
                }
                total = response.totalBytes ?: total

                val buffer = ByteArray(BUFFER_SIZE)
                var written = offset
                while (true) {
                    val read = response.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        files.append(path, buffer, read)
                        written += read
                        onBytes?.invoke(trackId, written, total)
                    }
                }

                val finalSize = files.sizeOf(path)
                upsertProgress(
                    id = trackId,
                    status = DownloadStatus.COMPLETED,
                    resumeOffset = finalSize,
                    bytesTotal = total ?: finalSize,
                    localPath = path,
                    error = null,
                    createdAt = existing?.createdAt ?: now(),
                )
                DownloadOutcome.Completed(path, finalSize, total)
            } finally {
                response.close()
            }
        } catch (e: IOException) {
            val onDisk = files.sizeOf(path)
            upsertProgress(
                id = trackId,
                status = DownloadStatus.FAILED,
                resumeOffset = onDisk,
                bytesTotal = total ?: 0,
                localPath = path,
                error = e.message ?: "I/O error",
                createdAt = existing?.createdAt ?: now(),
            )
            DownloadOutcome.Failed(onDisk, e)
        }
    }

    private suspend fun upsertProgress(
        id: String,
        status: String,
        resumeOffset: Long,
        bytesTotal: Long,
        localPath: String?,
        error: String?,
        createdAt: Long,
    ) {
        dao.upsert(
            DownloadEntity(
                id = id,
                status = status,
                localPath = localPath,
                resumeOffset = resumeOffset,
                bytesTotal = bytesTotal,
                error = error,
                createdAt = createdAt,
                updatedAt = now(),
            ),
        )
    }
}

/** Result of a [TrackDownloader.download] attempt. */
sealed interface DownloadOutcome {
    data class Completed(val localPath: String, val bytesWritten: Long, val totalBytes: Long?) : DownloadOutcome
    data class Failed(val bytesOnDisk: Long, val cause: Throwable) : DownloadOutcome
    data class AlreadyComplete(val localPath: String, val bytesOnDisk: Long) : DownloadOutcome
}
