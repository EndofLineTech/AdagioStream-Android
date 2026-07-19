package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Auto-play-next selection tests (beads_adagio-59p.2.2): the pure
 * [nextEpisode] selector under all three [PodcastEpisodeEndBehavior]s
 * (undated exclusion, all-finished → null, both display orders), and
 * [selectNextEpisode]'s lazy candidate-by-candidate progress hydration.
 */
class PodcastNextEpisodeTest {

    private val jan1 = "Thu, 01 Jan 2026 00:00:00 GMT"
    private val feb1 = "Sun, 01 Feb 2026 00:00:00 GMT"
    private val mar1 = "Sun, 01 Mar 2026 00:00:00 GMT"
    private val apr1 = "Wed, 01 Apr 2026 00:00:00 GMT"

    private fun episode(id: String, pubDate: String? = null) =
        AbsEpisode(id = id, title = "Episode $id", pubDate = pubDate)

    // Wire order scrambled on purpose — selection must go by parsed dates.
    private val episodes = listOf(
        episode("mar", mar1),
        episode("jan", jan1),
        episode("undated"),
        episode("apr", apr1),
        episode("feb", feb1),
    )

    private val none: (String) -> Boolean = { false }

    private fun next(
        after: String,
        behavior: PodcastEpisodeEndBehavior,
        order: PodcastEpisodeOrder = PodcastEpisodeOrder.NEWEST_FIRST,
        isFinished: (String) -> Boolean = none,
    ) = nextEpisode(episodes, after, order, behavior, isFinished)?.id

    // ---- stop --------------------------------------------------------------

    @Test
    fun `stop never selects anything`() {
        assertNull(next("jan", PodcastEpisodeEndBehavior.STOP))
        assertNull(next("apr", PodcastEpisodeEndBehavior.STOP))
        assertNull(next("undated", PodcastEpisodeEndBehavior.STOP))
    }

    // ---- nextUnplayed ------------------------------------------------------

    @Test
    fun `nextUnplayed picks the closest strictly newer episode`() {
        assertEquals("feb", next("jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED))
        assertEquals("apr", next("mar", PodcastEpisodeEndBehavior.NEXT_UNPLAYED))
    }

    @Test
    fun `nextUnplayed skips finished episodes`() {
        assertEquals(
            "mar",
            next("jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED, isFinished = { it == "feb" }),
        )
    }

    @Test
    fun `nextUnplayed ignores the display order`() {
        assertEquals(
            "feb",
            next("jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED, order = PodcastEpisodeOrder.OLDEST_FIRST),
        )
    }

    @Test
    fun `nextUnplayed from the newest episode stops`() {
        assertNull(next("apr", PodcastEpisodeEndBehavior.NEXT_UNPLAYED))
    }

    @Test
    fun `nextUnplayed never selects undated episodes`() {
        // "undated" would be the only remaining candidate — still null.
        assertNull(
            next("mar", PodcastEpisodeEndBehavior.NEXT_UNPLAYED, isFinished = { it == "apr" }),
        )
    }

    @Test
    fun `nextUnplayed from an undated current episode stops`() {
        assertNull(next("undated", PodcastEpisodeEndBehavior.NEXT_UNPLAYED))
    }

    @Test
    fun `nextUnplayed with everything newer finished stops`() {
        assertNull(
            next("feb", PodcastEpisodeEndBehavior.NEXT_UNPLAYED, isFinished = { it in setOf("mar", "apr") }),
        )
    }

    @Test
    fun `nextUnplayed from an unknown episode id stops`() {
        assertNull(next("ghost", PodcastEpisodeEndBehavior.NEXT_UNPLAYED))
    }

    // ---- continueInSortOrder ----------------------------------------------

    @Test
    fun `continueInSortOrder walks newest-first display order downward`() {
        // newestFirst display: apr, mar, feb, jan, undated.
        assertEquals(
            "feb",
            next("mar", PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER),
        )
    }

    @Test
    fun `continueInSortOrder walks oldest-first display order upward`() {
        // oldestFirst display: jan, feb, mar, apr, undated.
        assertEquals(
            "mar",
            next("feb", PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER, order = PodcastEpisodeOrder.OLDEST_FIRST),
        )
    }

    @Test
    fun `continueInSortOrder skips finished episodes`() {
        assertEquals(
            "jan",
            next("mar", PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER, isFinished = { it == "feb" }),
        )
    }

    @Test
    fun `continueInSortOrder can select an undated episode at the tail`() {
        // Undated sorts last in the display order and IS a valid next slot.
        assertEquals(
            "undated",
            next("jan", PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER),
        )
    }

    @Test
    fun `continueInSortOrder stops at the end of the display order`() {
        assertNull(next("undated", PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER))
    }

    @Test
    fun `continueInSortOrder with everything remaining finished stops`() {
        assertNull(
            next(
                "apr",
                PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER,
                isFinished = { it in setOf("mar", "feb", "jan", "undated") },
            ),
        )
    }

    @Test
    fun `continueInSortOrder from an unknown episode id stops`() {
        assertNull(next("ghost", PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER))
    }

    // ---- selectNextEpisode (lazy hydration) --------------------------------

    private fun context(order: PodcastEpisodeOrder = PodcastEpisodeOrder.NEWEST_FIRST) =
        PodcastPlaybackContext("show1", "The Show", episodes, order)

    @Test
    fun `hydrates only until the selector converges`() = runTest {
        val fetched = mutableListOf<String>()
        // feb is finished server-side; mar is not.
        val next = selectNextEpisode(context(), "jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED) { id ->
            fetched += id
            if (id == "feb") AbsMediaProgress(isFinished = true) else null
        }
        assertEquals("mar", next?.id)
        // Exactly the candidates walked, each fetched once — never the whole show.
        assertEquals(listOf("feb", "mar"), fetched)
    }

    @Test
    fun `an unplayed first candidate costs exactly one fetch`() = runTest {
        val fetched = mutableListOf<String>()
        val next = selectNextEpisode(context(), "jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED) { id ->
            fetched += id
            null
        }
        assertEquals("feb", next?.id)
        assertEquals(listOf("feb"), fetched)
    }

    @Test
    fun `all candidates finished converges to null without refetching`() = runTest {
        val fetched = mutableListOf<String>()
        val next = selectNextEpisode(context(), "jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED) { id ->
            fetched += id
            AbsMediaProgress(isFinished = true)
        }
        assertNull(next)
        // Each dated-newer candidate hydrated exactly once.
        assertEquals(listOf("feb", "mar", "apr"), fetched)
    }

    @Test
    fun `near-complete progress counts as finished during selection`() = runTest {
        val next = selectNextEpisode(context(), "jan", PodcastEpisodeEndBehavior.NEXT_UNPLAYED) { id ->
            if (id == "feb") AbsMediaProgress(progress = 0.995, isFinished = false) else null
        }
        assertEquals("mar", next?.id)
    }

    @Test
    fun `stop behavior never hydrates`() = runTest {
        val fetched = mutableListOf<String>()
        val next = selectNextEpisode(context(), "jan", PodcastEpisodeEndBehavior.STOP) { id ->
            fetched += id
            null
        }
        assertNull(next)
        assertEquals(emptyList<String>(), fetched)
    }
}
