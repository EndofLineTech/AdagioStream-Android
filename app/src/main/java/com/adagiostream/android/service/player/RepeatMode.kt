package com.adagiostream.android.service.player

/**
 * Repeat mode for the library playback queue (baw.3.1). Radio is unaffected.
 *
 * - [Off]: play through the queue once and stop at the end.
 * - [All]: after the last track, wrap to the start and continue.
 * - [One]: on natural track-end, restart the same track. Manual next/previous
 *   still moves to the adjacent track — [One] only governs auto-advance, not
 *   user-initiated navigation.
 */
enum class RepeatMode {
    Off,
    All,
    One;

    /** The mode that follows this one when the user cycles: Off → All → One → Off. */
    val next: RepeatMode
        get() = when (this) {
            Off -> All
            All -> One
            One -> Off
        }
}
