package com.adagiostream.android.testutil

/**
 * Factory methods for time windows relative to "now", used for EPGEntry tests.
 * Uses generous windows (1 hour) to avoid flakiness.
 */
object TimeTestHelper {
    private const val ONE_HOUR_MS = 3_600_000L

    /** Returns (start, end) where now is inside the window. */
    fun currentlyAiringWindow(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return (now - ONE_HOUR_MS) to (now + ONE_HOUR_MS)
    }

    /** Returns (start, end) entirely in the future. */
    fun upcomingWindow(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return (now + ONE_HOUR_MS) to (now + 2 * ONE_HOUR_MS)
    }

    /** Returns (start, end) entirely in the past. */
    fun pastWindow(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return (now - 2 * ONE_HOUR_MS) to (now - ONE_HOUR_MS)
    }
}
