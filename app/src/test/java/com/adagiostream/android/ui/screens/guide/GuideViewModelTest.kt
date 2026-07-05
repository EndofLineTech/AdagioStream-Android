package com.adagiostream.android.ui.screens.guide

import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideViewModelTest {

    private val now = 1_000_000L

    @Test
    fun `buildChannelInfo picks the entry airing at now as current`() {
        val channel = TestFixtures.makeChannel()
        val airing = TestFixtures.makeEPGEntry(title = "Airing Now", start = now - 1000, end = now + 1000)
        val past = TestFixtures.makeEPGEntry(title = "Already Over", start = now - 5000, end = now - 1000)

        val info = GuideViewModel.buildChannelInfo(channel, listOf(past, airing), now)

        assertEquals("Airing Now", info.current?.title)
    }

    @Test
    fun `buildChannelInfo current is null when nothing is airing`() {
        val channel = TestFixtures.makeChannel()
        val past = TestFixtures.makeEPGEntry(title = "Already Over", start = now - 5000, end = now - 1000)
        val future = TestFixtures.makeEPGEntry(title = "Later", start = now + 1000, end = now + 2000)

        val info = GuideViewModel.buildChannelInfo(channel, listOf(past, future), now)

        assertNull(info.current)
    }

    @Test
    fun `buildChannelInfo boundary — entry ending exactly at now is not current`() {
        val channel = TestFixtures.makeChannel()
        val justEnded = TestFixtures.makeEPGEntry(title = "Just Ended", start = now - 1000, end = now)

        val info = GuideViewModel.buildChannelInfo(channel, listOf(justEnded), now)

        assertNull(info.current)
    }

    @Test
    fun `buildChannelInfo boundary — entry starting exactly at now is current`() {
        val channel = TestFixtures.makeChannel()
        val startingNow = TestFixtures.makeEPGEntry(title = "Starting Now", start = now, end = now + 1000)

        val info = GuideViewModel.buildChannelInfo(channel, listOf(startingNow), now)

        assertEquals("Starting Now", info.current?.title)
    }

    @Test
    fun `buildChannelInfo upcoming is sorted by start time ascending`() {
        val channel = TestFixtures.makeChannel()
        val later = TestFixtures.makeEPGEntry(title = "Later", start = now + 5000, end = now + 6000)
        val sooner = TestFixtures.makeEPGEntry(title = "Sooner", start = now + 1000, end = now + 2000)

        val info = GuideViewModel.buildChannelInfo(channel, listOf(later, sooner), now)

        assertEquals(listOf("Sooner", "Later"), info.upcoming.map { it.title })
    }

    @Test
    fun `buildChannelInfo upcoming excludes past and currently-airing entries`() {
        val channel = TestFixtures.makeChannel()
        val past = TestFixtures.makeEPGEntry(title = "Past", start = now - 2000, end = now - 1000)
        val airing = TestFixtures.makeEPGEntry(title = "Airing", start = now - 500, end = now + 500)
        val next = TestFixtures.makeEPGEntry(title = "Next", start = now + 1000, end = now + 2000)

        val info = GuideViewModel.buildChannelInfo(channel, listOf(past, airing, next), now)

        assertEquals(listOf("Next"), info.upcoming.map { it.title })
    }

    @Test
    fun `buildChannelInfo upcoming is capped at UPCOMING_LIMIT`() {
        val channel = TestFixtures.makeChannel()
        val entries = (0 until GuideViewModel.UPCOMING_LIMIT + 5).map { i ->
            TestFixtures.makeEPGEntry(
                title = "Show $i",
                start = now + (i + 1) * 1000L,
                end = now + (i + 2) * 1000L,
            )
        }

        val info = GuideViewModel.buildChannelInfo(channel, entries, now)

        assertEquals(GuideViewModel.UPCOMING_LIMIT, info.upcoming.size)
    }

    @Test
    fun `buildChannelInfo with no entries returns null current and empty upcoming`() {
        val channel = TestFixtures.makeChannel()

        val info = GuideViewModel.buildChannelInfo(channel, emptyList(), now)

        assertNull(info.current)
        assertEquals(emptyList<Any>(), info.upcoming)
    }
}
