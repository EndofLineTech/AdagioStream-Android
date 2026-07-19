package com.adagiostream.android.service.player

/**
 * Narrow port the AudiobookPlaybackCoordinator uses to render audio for the
 * SINGLE audiobook file it has active — the multi-file timeline, chapter and
 * sync logic all live in the coordinator (beads_adagio-59p.1.5).
 *
 * Mirrors [LibraryTrackPlayer]: [VLCPlayerWrapper] is the production
 * implementation; the interface exists so the coordinator is unit-testable on
 * the JVM with a fake player (libVLC cannot be instantiated there).
 */
interface AudiobookFilePlayer {

    /**
     * Starts playback of one audiobook file from [streamUrl], marking the
     * player's active source as [source]. [startPositionMs] is the offset
     * WITHIN THIS FILE (applied at open via `:start-time`, like the library
     * path). [rate] is the playback speed to apply — libVLC resets the rate on
     * every media change, so it must be re-applied per file load.
     */
    fun playAudiobookFile(
        streamUrl: String,
        source: PlaybackSource.Audiobook,
        startPositionMs: Long = 0L,
        rate: Float = 1.0f,
    )

    /**
     * Refreshes audiobook metadata (chapter title) WITHOUT restarting playback
     * — no-op when no audiobook is active, so a stale update can't override a
     * live radio/library source.
     */
    fun updateAudiobookSource(source: PlaybackSource.Audiobook)

    /** Seeks within the CURRENTLY LOADED file (file-local milliseconds). */
    fun seekToPositionMs(positionMs: Long)

    /** Position within the currently loaded file, in milliseconds. */
    fun currentPositionMs(): Long

    /** Applies a playback rate to the active media. */
    fun setRate(rate: Float)

    /** Stops playback entirely. */
    fun stop()
}
