package com.adagiostream.android.service.player

/**
 * Narrow port the [MusicPlaybackCoordinator] uses to drive actual audio playback
 * of a library track, decoupled from the native libVLC implementation (baw.3.2).
 *
 * [VLCPlayerWrapper] is the production implementation. The interface exists so the
 * coordinator — which owns the queue-advance / auto-advance / shuffle-repeat
 * decision logic — can be unit-tested with a fake player on the JVM, where libVLC
 * cannot be instantiated.
 */
interface LibraryTrackPlayer {

    /**
     * Starts playback of a library track from [streamUrl] and marks the player's
     * active source as [source] (so [PlaybackContract] routes EndReached to
     * auto-advance and exposes seek/duration for the now-playing surface).
     *
     * Implementations stop any current radio/library playback first.
     *
     * @param startPositionMs when > 0, playback starts at this offset instead of
     *   the beginning (baw.10 resume-at-position). Implementations should apply
     *   this at open time (e.g. a VLC `start-time` media option) rather than
     *   seeking after playback starts, so there is no audible blip from the
     *   track's intro before the seek lands (baw.11 — the blip iOS had to mute
     *   around doesn't exist here as long as the offset is structural).
     */
    fun playLibraryTrack(streamUrl: String, source: PlaybackSource.Library, startPositionMs: Long = 0L)

    /** Stops playback entirely (queue exhausted with no repeat). */
    fun stop()
}
