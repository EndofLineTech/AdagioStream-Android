package com.adagiostream.android.service.download

import com.adagiostream.android.service.audiobookshelf.AbsAudioTrack
import com.adagiostream.android.service.audiobookshelf.AbsChapter
import com.adagiostream.android.service.audiobookshelf.AudiobookTimeline
import com.adagiostream.android.service.library.db.AudiobookDownloadEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Typed accessors for the [AudiobookDownloadEntity] JSON manifest columns
 * (beads_adagio-59p.1.6, port of iOS `AudiobookDownload.swift`).
 *
 * The manifest preserves each file's book-global `startOffset`/`duration`
 * verbatim from the streaming session at download time, so
 * [offlineTimeline] rebuilds the SAME [AudiobookTimeline] a live `/play`
 * session would produce — offline playback chains files and maps global↔file
 * time exactly as streaming does, just with `file://` content URLs.
 */

/**
 * One audio file of a downloaded book. [ino] is the server file inode used by
 * `GET /api/items/{id}/file/{ino}/download`; [ext] is the file extension taken
 * from the session `contentUrl` (so the on-disk file keeps a playable suffix).
 * [localPath] is set only once the file has fully downloaded.
 */
@Serializable
data class AudiobookDownloadFile(
    val index: Int,
    val ino: String,
    val startOffset: Double,
    val duration: Double,
    val ext: String? = null,
    val localPath: String? = null,
)

private val manifestJson = Json { ignoreUnknownKeys = true }
private val filesSerializer = ListSerializer(AudiobookDownloadFile.serializer())
private val chaptersSerializer = ListSerializer(AbsChapter.serializer())

/** Decodes [AudiobookDownloadEntity.filesJson]; corrupt JSON degrades to empty. */
fun AudiobookDownloadEntity.manifestFiles(): List<AudiobookDownloadFile> = try {
    manifestJson.decodeFromString(filesSerializer, filesJson)
} catch (_: Exception) {
    emptyList()
}

/** Decodes [AudiobookDownloadEntity.chaptersJson]; corrupt JSON degrades to empty. */
fun AudiobookDownloadEntity.manifestChapters(): List<AbsChapter> = try {
    manifestJson.decodeFromString(chaptersSerializer, chaptersJson)
} catch (_: Exception) {
    emptyList()
}

fun encodeManifestFiles(files: List<AudiobookDownloadFile>): String =
    manifestJson.encodeToString(filesSerializer, files)

fun encodeManifestChapters(chapters: List<AbsChapter>): String =
    manifestJson.encodeToString(chaptersSerializer, chapters)

/** True when every manifest file has a localPath that exists on disk. */
fun AudiobookDownloadEntity.allFilesPresent(files: DownloadFileStore): Boolean {
    val manifest = manifestFiles()
    return manifest.isNotEmpty() && manifest.all { f ->
        f.localPath != null && files.exists(f.localPath) && files.sizeOf(f.localPath) > 0
    }
}

/**
 * Rebuilds the book's [AudiobookTimeline] from the persisted manifest with
 * `file://` content URLs. Files without a local path yet are skipped (mirrors
 * iOS `AudiobookDownloadRecord.timeline()`).
 */
fun AudiobookDownloadEntity.offlineTimeline(): AudiobookTimeline {
    val tracks = manifestFiles().mapNotNull { f ->
        val path = f.localPath ?: return@mapNotNull null
        AbsAudioTrack(
            index = f.index,
            startOffset = f.startOffset,
            duration = f.duration,
            title = null,
            contentUrl = LocalFirstResolver.fileUri(path),
        )
    }
    return AudiobookTimeline(tracks, manifestChapters())
}
