package com.adagiostream.android.service.audiobookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure podcast core tests (beads_adagio-59p.2.1): the RFC-2822 pubDate
 * parser, [sortedEpisodes] in both orders with undated episodes stable-last,
 * the finished epsilon boundary, the items-in-progress partition, and the
 * progress-badge state mapping.
 */
class PodcastEpisodesTest {

    private fun episode(id: String, pubDate: String? = null) =
        AbsEpisode(id = id, title = "Episode $id", pubDate = pubDate)

    private fun progress(fraction: Double? = null, finished: Boolean? = null) =
        AbsMediaProgress(progress = fraction, isFinished = finished)

    // -------------------------------------------------------------------------
    // parsePubDate
    // -------------------------------------------------------------------------

    @Test
    fun `parses RFC-2822 GMT date`() {
        // Mon, 05 Jan 2026 10:30:00 GMT
        assertEquals(1767609000000L, parsePubDate("Mon, 05 Jan 2026 10:30:00 GMT"))
    }

    @Test
    fun `parses numeric zone offset variant`() {
        // Same instant expressed as +0000 and as -0500.
        assertEquals(1767609000000L, parsePubDate("Mon, 05 Jan 2026 10:30:00 +0000"))
        assertEquals(1767609000000L, parsePubDate("Mon, 05 Jan 2026 05:30:00 -0500"))
    }

    @Test
    fun `parses named zone variant`() {
        assertNotNull(parsePubDate("Mon, 05 Jan 2026 10:30:00 EST"))
    }

    @Test
    fun `garbage, missing, and empty pubDates parse to null`() {
        assertNull(parsePubDate("not a date"))
        assertNull(parsePubDate(null))
        assertNull(parsePubDate(""))
        assertNull(parsePubDate("   "))
    }

    @Test
    fun `formatPubDate returns null for unparseable input`() {
        assertNull(formatPubDate("garbage"))
        assertNull(formatPubDate(null))
        assertNotNull(formatPubDate("Mon, 05 Jan 2026 10:30:00 GMT"))
    }

    // -------------------------------------------------------------------------
    // sortedEpisodes
    // -------------------------------------------------------------------------

    private val jan1 = "Thu, 01 Jan 2026 00:00:00 GMT"
    private val feb1 = "Sun, 01 Feb 2026 00:00:00 GMT"
    private val mar1 = "Sun, 01 Mar 2026 00:00:00 GMT"

    @Test
    fun `newestFirst sorts by parsed date descending`() {
        val episodes = listOf(episode("a", jan1), episode("b", mar1), episode("c", feb1))
        assertEquals(
            listOf("b", "c", "a"),
            sortedEpisodes(episodes, PodcastEpisodeOrder.NEWEST_FIRST).map { it.id },
        )
    }

    @Test
    fun `oldestFirst sorts by parsed date ascending`() {
        val episodes = listOf(episode("a", feb1), episode("b", jan1), episode("c", mar1))
        assertEquals(
            listOf("b", "a", "c"),
            sortedEpisodes(episodes, PodcastEpisodeOrder.OLDEST_FIRST).map { it.id },
        )
    }

    @Test
    fun `undated and unparseable episodes sort last in stable wire order — both orders`() {
        val episodes = listOf(
            episode("undated1"),
            episode("dated1", feb1),
            episode("garbage", "not a date"),
            episode("dated2", jan1),
            episode("undated2"),
        )
        assertEquals(
            listOf("dated1", "dated2", "undated1", "garbage", "undated2"),
            sortedEpisodes(episodes, PodcastEpisodeOrder.NEWEST_FIRST).map { it.id },
        )
        assertEquals(
            listOf("dated2", "dated1", "undated1", "garbage", "undated2"),
            sortedEpisodes(episodes, PodcastEpisodeOrder.OLDEST_FIRST).map { it.id },
        )
    }

    @Test
    fun `equal dates keep stable wire order`() {
        val episodes = listOf(episode("first", jan1), episode("second", jan1))
        assertEquals(
            listOf("first", "second"),
            sortedEpisodes(episodes, PodcastEpisodeOrder.NEWEST_FIRST).map { it.id },
        )
    }

    @Test
    fun `empty list sorts to empty`() {
        assertEquals(emptyList<AbsEpisode>(), sortedEpisodes(emptyList(), PodcastEpisodeOrder.NEWEST_FIRST))
    }

    // -------------------------------------------------------------------------
    // Finished epsilon
    // -------------------------------------------------------------------------

    @Test
    fun `finished epsilon boundary`() {
        assertTrue(isFinishedProgress(progress(fraction = 0.99)))
        assertTrue(isFinishedProgress(progress(fraction = 1.0)))
        assertFalse(isFinishedProgress(progress(fraction = 0.989)))
        assertTrue(isFinishedProgress(progress(fraction = 0.1, finished = true)))
        assertFalse(isFinishedProgress(progress(fraction = null, finished = false)))
        assertFalse(isFinishedProgress(null)) // never started / 404 → unplayed
    }

    // -------------------------------------------------------------------------
    // Continue Listening partition
    // -------------------------------------------------------------------------

    @Test
    fun `partition splits books and podcast episodes by recentEpisode`() {
        val items = listOf(
            AbsLibraryItem(
                id = "bk1",
                userMediaProgress = progress(fraction = 0.4),
            ),
            AbsLibraryItem(
                id = "show1",
                media = AbsMedia(metadata = AbsMetadata(title = "The Show")),
                recentEpisode = episode("ep1", jan1),
            ),
            AbsLibraryItem(
                id = "bk2",
                userMediaProgress = progress(fraction = 0.7),
            ),
        )

        val partition = partitionItemsInProgress(items)

        assertEquals(listOf("bk1", "bk2"), partition.books.map { it.id })
        assertEquals(listOf("ep1"), partition.episodes.map { it.episode.id })
        assertEquals("show1", partition.episodes.single().showLibraryItemId)
        assertEquals("The Show", partition.episodes.single().showTitle)
    }

    @Test
    fun `partition filters unstarted and finished books but keeps all episodes`() {
        val items = listOf(
            AbsLibraryItem(id = "unstarted", userMediaProgress = progress(fraction = 0.0)),
            AbsLibraryItem(id = "noProgress"),
            AbsLibraryItem(id = "done", userMediaProgress = progress(fraction = 0.995)),
            AbsLibraryItem(id = "flagged", userMediaProgress = progress(fraction = 0.5, finished = true)),
            AbsLibraryItem(id = "reading", userMediaProgress = progress(fraction = 0.5)),
            AbsLibraryItem(id = "show1", recentEpisode = episode("ep1")),
        )

        val partition = partitionItemsInProgress(items)

        assertEquals(listOf("reading"), partition.books.map { it.id })
        assertEquals(listOf("ep1"), partition.episodes.map { it.episode.id })
    }

    @Test
    fun `partition preserves server order within each group`() {
        val items = listOf(
            AbsLibraryItem(id = "s1", recentEpisode = episode("e1")),
            AbsLibraryItem(id = "b1", userMediaProgress = progress(fraction = 0.2)),
            AbsLibraryItem(id = "s2", recentEpisode = episode("e2")),
            AbsLibraryItem(id = "b2", userMediaProgress = progress(fraction = 0.3)),
        )
        val partition = partitionItemsInProgress(items)
        assertEquals(listOf("b1", "b2"), partition.books.map { it.id })
        assertEquals(listOf("e1", "e2"), partition.episodes.map { it.episode.id })
    }

    // -------------------------------------------------------------------------
    // Progress badge state mapping
    // -------------------------------------------------------------------------

    @Test
    fun `badge state maps hydrated progress`() {
        assertEquals(EpisodeProgressState.Unplayed, episodeProgressState(null))
        assertEquals(EpisodeProgressState.Unplayed, episodeProgressState(progress(fraction = 0.0)))
        assertEquals(
            EpisodeProgressState.InProgress(0.5),
            episodeProgressState(progress(fraction = 0.5)),
        )
        assertEquals(EpisodeProgressState.Finished, episodeProgressState(progress(fraction = 0.99)))
        assertEquals(
            EpisodeProgressState.Finished,
            episodeProgressState(progress(fraction = 0.2, finished = true)),
        )
    }

    @Test
    fun `badge state clamps an out-of-range fraction`() {
        assertEquals(
            EpisodeProgressState.InProgress(0.5),
            episodeProgressState(progress(fraction = 0.5)),
        )
        // > 1.0 without the flag is >= the finished epsilon anyway.
        assertEquals(EpisodeProgressState.Finished, episodeProgressState(progress(fraction = 1.5)))
    }
}
