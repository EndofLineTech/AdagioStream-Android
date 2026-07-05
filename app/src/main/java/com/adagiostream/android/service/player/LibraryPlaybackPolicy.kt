package com.adagiostream.android.service.player

import androidx.media3.common.Player

// ============================================================================
// LibraryPlaybackPolicy — pure, fully unit-testable decision logic for the
// library playback engine (baw.3.3 / baw.3.5 / baw.3.6).
//
// The VLCSessionPlayer / VLCPlayerWrapper classes can't be instantiated on the
// JVM (native libVLC). The non-trivial decisions they make for library playback
// are factored out HERE so they can be tested without a device, mirroring how
// PlaybackContract factored out the live-vs-library contract.
// ============================================================================

/**
 * What to do when a library track finishes naturally (libVLC EndReached +
 * [EndReachedPolicy.AutoAdvance]).
 *
 * [RepeatMode.One] restarts the same track; otherwise the queue advances. The
 * actual "which track is next / is there a next" decision stays in
 * [MusicQueueManager.next]; this only encodes the repeat-one branch the queue
 * deliberately does NOT handle (One governs auto-advance only, never manual nav).
 */
enum class AutoAdvanceAction {
    /** Repeat-one: restart the current track from the top. */
    RestartCurrent,

    /** Advance to the next queued track (honouring repeat-all wrap in the queue). */
    AdvanceToNext,
}

/** Pure auto-advance decision: repeat-one restarts, everything else advances. */
fun autoAdvanceAction(repeatMode: RepeatMode): AutoAdvanceAction =
    if (repeatMode == RepeatMode.One) AutoAdvanceAction.RestartCurrent
    else AutoAdvanceAction.AdvanceToNext

/**
 * Bidirectional mapping between the app's [RepeatMode] and Media3's
 * `Player.REPEAT_MODE_*` ints, used to expose/receive repeat state through the
 * MediaSession (baw.3.5).
 */
object Media3RepeatMapping {

    /** App repeat mode → Media3 `Player.REPEAT_MODE_*`. */
    fun toMedia3(mode: RepeatMode): Int = when (mode) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.One -> Player.REPEAT_MODE_ONE
        RepeatMode.All -> Player.REPEAT_MODE_ALL
    }

    /** Media3 `Player.REPEAT_MODE_*` → app repeat mode (unknown values map to Off). */
    fun fromMedia3(value: Int): RepeatMode = when (value) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }
}

/**
 * Maps a requested scrubber position onto a valid libVLC seek time (baw.3.6).
 *
 * Clamps the request into `[0, durationMs]` when the duration is known and
 * positive; an unknown/non-positive duration only clamps the lower bound (so a
 * still-loading track does not reject a forward seek).
 */
fun clampSeekMs(requestedMs: Long, durationMs: Long): Long {
    val lowerBounded = requestedMs.coerceAtLeast(0L)
    return if (durationMs > 0L) lowerBounded.coerceAtMost(durationMs) else lowerBounded
}

/**
 * Scrobble submission threshold logic (baw.5.1).
 *
 * Mirrors the Last.fm / Subsonic scrobble spec:
 *  - Submit when 50% of the track duration has elapsed, OR
 *  - Submit when 4 minutes (240 s) have elapsed (whichever comes first).
 *
 * A zero or null duration is treated as unknown — only the 4-minute fallback
 * rule applies in that case.
 */
object LibraryPlaybackPolicy {
    fun shouldSubmit(elapsedSeconds: Long, durationSeconds: Long?): Boolean {
        val duration = durationSeconds?.takeIf { it > 0 }
        if (duration != null && elapsedSeconds * 2 >= duration) return true
        return elapsedSeconds >= 240
    }
}
