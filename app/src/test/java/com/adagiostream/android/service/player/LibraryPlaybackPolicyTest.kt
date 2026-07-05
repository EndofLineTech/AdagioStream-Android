package com.adagiostream.android.service.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure decision logic for the library playback engine (baw.3.3 / baw.3.5 / baw.3.6).
 *
 * These guard the branches the native player classes can't unit-test: auto-advance
 * vs repeat-one restart, the RepeatMode ↔ Media3 mapping exposed through the
 * MediaSession, and scrubber position clamping.
 */
class LibraryPlaybackPolicyTest {

    // ---- autoAdvanceAction ------------------------------------------------

    @Test
    fun `repeat one restarts the current track on auto-advance`() {
        assertEquals(AutoAdvanceAction.RestartCurrent, autoAdvanceAction(RepeatMode.One))
    }

    @Test
    fun `repeat off advances to the next track on auto-advance`() {
        assertEquals(AutoAdvanceAction.AdvanceToNext, autoAdvanceAction(RepeatMode.Off))
    }

    @Test
    fun `repeat all advances to the next track on auto-advance (queue wraps)`() {
        assertEquals(AutoAdvanceAction.AdvanceToNext, autoAdvanceAction(RepeatMode.All))
    }

    // ---- Media3RepeatMapping ---------------------------------------------

    @Test
    fun `repeat mode maps to the matching Media3 constant`() {
        assertEquals(Player.REPEAT_MODE_OFF, Media3RepeatMapping.toMedia3(RepeatMode.Off))
        assertEquals(Player.REPEAT_MODE_ONE, Media3RepeatMapping.toMedia3(RepeatMode.One))
        assertEquals(Player.REPEAT_MODE_ALL, Media3RepeatMapping.toMedia3(RepeatMode.All))
    }

    @Test
    fun `Media3 constant maps back to the matching repeat mode`() {
        assertEquals(RepeatMode.Off, Media3RepeatMapping.fromMedia3(Player.REPEAT_MODE_OFF))
        assertEquals(RepeatMode.One, Media3RepeatMapping.fromMedia3(Player.REPEAT_MODE_ONE))
        assertEquals(RepeatMode.All, Media3RepeatMapping.fromMedia3(Player.REPEAT_MODE_ALL))
    }

    @Test
    fun `every repeat mode round-trips through Media3`() {
        for (mode in RepeatMode.entries) {
            assertEquals(mode, Media3RepeatMapping.fromMedia3(Media3RepeatMapping.toMedia3(mode)))
        }
    }

    // ---- clampSeekMs ------------------------------------------------------

    @Test
    fun `seek clamps to zero when negative`() {
        assertEquals(0L, clampSeekMs(requestedMs = -5_000L, durationMs = 180_000L))
    }

    @Test
    fun `seek clamps to duration when past the end`() {
        assertEquals(180_000L, clampSeekMs(requestedMs = 999_000L, durationMs = 180_000L))
    }

    @Test
    fun `seek passes through a valid in-range position`() {
        assertEquals(42_000L, clampSeekMs(requestedMs = 42_000L, durationMs = 180_000L))
    }

    @Test
    fun `seek with unknown duration only clamps the lower bound`() {
        assertEquals(42_000L, clampSeekMs(requestedMs = 42_000L, durationMs = 0L))
        assertEquals(0L, clampSeekMs(requestedMs = -1L, durationMs = 0L))
    }
}
