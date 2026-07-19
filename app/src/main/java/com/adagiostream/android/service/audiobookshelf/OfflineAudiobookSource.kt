package com.adagiostream.android.service.audiobookshelf

/**
 * A fully-downloaded book, ready for offline playback (beads_adagio-59p.1.6).
 *
 * [timeline] is rebuilt from the persisted download manifest with `file://`
 * content URLs; [currentTime] is the manifest-cached last-known position —
 * offline resume precedence level below the pending sync-queue position.
 */
data class OfflineAudiobook(
    val libraryItemId: String,
    val title: String,
    val author: String?,
    /** Local path of the cached cover image, or null. */
    val coverPath: String?,
    /** Manifest-cached last-known global position (seconds). */
    val currentTime: Double,
    val timeline: AudiobookTimeline,
)

/**
 * The [AudiobookPlaybackCoordinator]'s window onto offline downloads —
 * implemented by the download manager, faked in tests (mirrors the
 * `DownloadLocator` seam on the music side).
 */
interface OfflineAudiobookSource {

    /** The book, if a COMPLETE local copy exists (all files on disk); else null. */
    suspend fun completeBook(libraryItemId: String): OfflineAudiobook?

    /** Persists the last playback position into the manifest for offline resume. */
    suspend fun savePosition(libraryItemId: String, seconds: Double)
}
