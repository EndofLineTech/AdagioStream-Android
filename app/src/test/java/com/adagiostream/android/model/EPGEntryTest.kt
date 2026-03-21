package com.adagiostream.android.model

import com.adagiostream.android.testutil.TimeTestHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EPGEntryTest {

    @Test
    fun `isCurrentlyAiring returns true when now is inside window`() {
        val (start, end) = TimeTestHelper.currentlyAiringWindow()
        val entry = EPGEntry(channelID = "ch1", title = "Live", start = start, end = end)
        assertTrue(entry.isCurrentlyAiring)
    }

    @Test
    fun `isCurrentlyAiring returns false for future entry`() {
        val (start, end) = TimeTestHelper.upcomingWindow()
        val entry = EPGEntry(channelID = "ch1", title = "Future", start = start, end = end)
        assertFalse(entry.isCurrentlyAiring)
    }

    @Test
    fun `isCurrentlyAiring returns false for past entry`() {
        val (start, end) = TimeTestHelper.pastWindow()
        val entry = EPGEntry(channelID = "ch1", title = "Past", start = start, end = end)
        assertFalse(entry.isCurrentlyAiring)
    }

    @Test
    fun `isUpcoming returns true for future entry`() {
        val (start, end) = TimeTestHelper.upcomingWindow()
        val entry = EPGEntry(channelID = "ch1", title = "Future", start = start, end = end)
        assertTrue(entry.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for currently airing entry`() {
        val (start, end) = TimeTestHelper.currentlyAiringWindow()
        val entry = EPGEntry(channelID = "ch1", title = "Live", start = start, end = end)
        assertFalse(entry.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for past entry`() {
        val (start, end) = TimeTestHelper.pastWindow()
        val entry = EPGEntry(channelID = "ch1", title = "Past", start = start, end = end)
        assertFalse(entry.isUpcoming)
    }

    @Test
    fun `description defaults to null`() {
        val entry = EPGEntry(channelID = "ch1", title = "Test", start = 0, end = 1000)
        assertEquals(null, entry.description)
    }

    @Test
    fun `data class equality works`() {
        val a = EPGEntry(channelID = "ch1", title = "Show", start = 100, end = 200)
        val b = EPGEntry(channelID = "ch1", title = "Show", start = 100, end = 200)
        assertEquals(a, b)
    }
}
