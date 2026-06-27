package com.adagiostream.android.service.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LibraryPlaybackPolicy.shouldSubmit] (baw.5.1).
 *
 * Spec: submit when ≥50% of the track duration has elapsed, OR when
 * ≥4 minutes (240 s) have elapsed — whichever comes first.
 * A zero or null duration activates the 4-minute-only fallback.
 */
class ScrobblerTest {

    // ---- 50 % threshold ---------------------------------------------------

    @Test
    fun `shouldSubmit returns false at 0 percent played`() {
        assertFalse(LibraryPlaybackPolicy.shouldSubmit(0, 300))
    }

    @Test
    fun `shouldSubmit returns true at exactly 50 percent`() {
        assertTrue(LibraryPlaybackPolicy.shouldSubmit(150, 300))
    }

    @Test
    fun `shouldSubmit returns false just under 50 percent`() {
        assertFalse(LibraryPlaybackPolicy.shouldSubmit(149, 300))
    }

    // ---- 4-minute fallback ------------------------------------------------

    @Test
    fun `shouldSubmit returns false at 4 min rule just under with null duration`() {
        assertFalse(LibraryPlaybackPolicy.shouldSubmit(239, null))
    }

    @Test
    fun `shouldSubmit returns true at exactly 4 min with null duration`() {
        assertTrue(LibraryPlaybackPolicy.shouldSubmit(240, null))
    }

    @Test
    fun `shouldSubmit returns false for zero duration treated as null`() {
        assertFalse(LibraryPlaybackPolicy.shouldSubmit(239, 0))
    }

    // ---- real-world mix ---------------------------------------------------

    @Test
    fun `shouldSubmit returns true at 55 percent of 3 min track`() {
        assertTrue(LibraryPlaybackPolicy.shouldSubmit(100, 180))
    }

    @Test
    fun `shouldSubmit returns false at 10 percent of 10 min track below 240s`() {
        assertFalse(LibraryPlaybackPolicy.shouldSubmit(60, 600))
    }

    @Test
    fun `shouldSubmit returns true at 40 percent of 10 min track when elapsed reaches 240s fallback`() {
        // 240 / 600 = 40% — below 50% threshold but ≥ 240 s fallback.
        assertTrue(LibraryPlaybackPolicy.shouldSubmit(240, 600))
    }
}
