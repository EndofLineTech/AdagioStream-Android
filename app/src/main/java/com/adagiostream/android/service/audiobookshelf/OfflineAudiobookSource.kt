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

    /**
     * The episode, if a COMPLETE local copy exists — keyed by the composite
     * (show, episode) download id so it never collides with the show's book id
     * or sibling episodes (bead .2.3).
     */
    suspend fun completeEpisode(showLibraryItemId: String, episodeId: String): OfflineAudiobook?

    /**
     * Persists the last playback position into the manifest for offline resume.
     * [episodeId] null = book (keyed by libraryItemId); non-null = episode
     * (keyed by the composite (show, episode) id).
     */
    suspend fun savePosition(libraryItemId: String, episodeId: String?, seconds: Double)

    /**
     * Deletes an episode's downloaded copy (files + manifest row). Backs the
     * auto-delete-after-played path (bead .2.3); no-op when nothing is stored.
     */
    suspend fun deleteEpisode(showLibraryItemId: String, episodeId: String)
}
