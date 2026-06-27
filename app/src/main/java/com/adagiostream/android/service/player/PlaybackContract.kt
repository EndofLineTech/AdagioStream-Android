package com.adagiostream.android.service.player

import androidx.media3.common.C

// ============================================================================
// PlaybackContract (baw.3.7 — DESIGN GATE)
//
// The single source of truth for how the player's per-source behaviour differs
// between live radio and on-demand library playback. VLCSessionPlayer.getState()
// and VLCPlayerWrapper's EndReached handler read their policy from HERE rather
// than hard-coding it, so the live-vs-library contract is locked by unit tests
// (PlaybackContractTest) — a future change that silently makes the live path
// seekable, gives it a finite duration, or stops treating a dropped live stream
// as a retryable error will fail CI.
//
// REGRESSION GUARD: the Radio (and null) branches below reproduce the EXACT
// values the live IPTV path has always emitted:
//   isSeekable=false, durationUs=TIME_UNSET, isDynamic=true, EndReached→retry.
// Do not change those without understanding that they ARE the live behaviour.
// ============================================================================

/**
 * What the player should do when libVLC reports `EndReached` for the active
 * source.
 *
 * libVLC fires `EndReached` both when a finite track finishes and when a live
 * stream drops (e.g. a wifi↔mobile network swap). The correct response is the
 * opposite in each case, so the source — not the event — decides.
 */
enum class EndReachedPolicy {
    /**
     * Live radio: a live stream should never legitimately end, so `EndReached`
     * is a transient failure. Retry the connection (existing live behaviour).
     */
    RetryAsError,

    /**
     * On-demand library: `EndReached` means the track finished. Auto-advance to
     * the next track in the queue (music semantics).
     */
    AutoAdvance,
}

/**
 * Pure, fully unit-testable policy mapping a [PlaybackSource] to the player's
 * seek / duration / dynamic-playlist / end-of-stream behaviour.
 *
 * A `null` source (nothing playing, or the seam not yet populated) is treated as
 * live — the conservative default that preserves the existing radio behaviour.
 */
object PlaybackContract {

    /** True only for on-demand library tracks; live streams are never seekable. */
    fun isSeekable(source: PlaybackSource?): Boolean = when (source) {
        is PlaybackSource.Library -> true
        is PlaybackSource.Radio, null -> false
    }

    /**
     * Track duration in microseconds for library items with a known, positive
     * duration; [C.TIME_UNSET] for live streams or unknown/zero durations
     * (matching the existing live MediaItemData).
     */
    fun durationUs(source: PlaybackSource?): Long = when (source) {
        is PlaybackSource.Library ->
            source.currentTrack.duration
                ?.takeIf { it > 0 }
                ?.let { it.toLong() * MICROS_PER_SECOND }
                ?: C.TIME_UNSET
        is PlaybackSource.Radio, null -> C.TIME_UNSET
    }

    /**
     * Whether the Media3 playlist window is dynamic (grows / has no fixed end).
     * Live radio is dynamic (existing behaviour); a finite-length library track
     * window is not.
     */
    fun isDynamic(source: PlaybackSource?): Boolean = when (source) {
        is PlaybackSource.Library -> false
        is PlaybackSource.Radio, null -> true
    }

    /** How to handle libVLC's `EndReached` for the active source. */
    fun endReachedPolicy(source: PlaybackSource?): EndReachedPolicy = when (source) {
        is PlaybackSource.Library -> EndReachedPolicy.AutoAdvance
        is PlaybackSource.Radio, null -> EndReachedPolicy.RetryAsError
    }

    private const val MICROS_PER_SECOND = 1_000_000L
}
