package com.adagiostream.android.service.player

import android.net.Uri
import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.service.audiobookshelf.PodcastShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [PodcastAutoMediaMapper] (beads_adagio-59p.2.4).
 *
 * Mirrors [AudiobookAutoMediaMapperTest]'s goals for the deeper podcast tree:
 *
 * 1. **ID round-trip** — library/show node IDs strip cleanly, AND the episode
 *    node id carries BOTH the show id and the episode id back out, even when
 *    both halves contain underscores (the exact reason `_` can't be the
 *    separator).
 * 2. **Picker-vs-flat decision** — a library picker level only when >1 podcast
 *    library exists; a single library goes straight to the show list.
 * 3. **Row shapes** — shows are browsable (author subtitle, cover), episodes
 *    are playable ("pubDate · duration" subtitle, show title as artist, cover).
 * 4. **Routing non-regression guard** — podcast IDs are never captured by the
 *    audiobook / music sub-trees, and vice versa.
 */
@RunWith(RobolectricTestRunner::class)
class PodcastAutoMediaMapperTest {

    private fun library(id: String, name: String = "Library $id") =
        AbsLibrary(id = id, name = name, mediaType = "podcast")

    private fun show(id: String, title: String = "Show $id", author: String? = "Author $id") =
        PodcastShow(id = id, libraryId = "lib", title = title, author = author)

    private fun episode(
        id: String,
        title: String? = "Episode $id",
        pubDate: String? = null,
        duration: Double? = null,
    ) = AbsEpisode(id = id, title = title, pubDate = pubDate, duration = duration)

    // =========================================================================
    // isPodcastId — routing guard
    // =========================================================================

    @Test
    fun `isPodcastId returns true for all podcast node IDs`() {
        assertTrue(PodcastAutoMediaMapper.isPodcastId(PodcastAutoMediaMapper.PODCASTS_ROOT_ID))
        assertTrue(PodcastAutoMediaMapper.isPodcastId("podcasts_library_lib1"))
        assertTrue(PodcastAutoMediaMapper.isPodcastId("podcasts_show_show1"))
        assertTrue(PodcastAutoMediaMapper.isPodcastId("podcasts_episode_show1|ep1"))
    }

    @Test
    fun `isPodcastId returns false for IPTV, music, and audiobook node IDs`() {
        assertFalse(PodcastAutoMediaMapper.isPodcastId("root"))
        assertFalse(PodcastAutoMediaMapper.isPodcastId("favorites"))
        assertFalse(PodcastAutoMediaMapper.isPodcastId("custom_playlist_abc"))
        assertFalse(PodcastAutoMediaMapper.isPodcastId("Sports"))
        assertFalse(PodcastAutoMediaMapper.isPodcastId(MusicAutoMediaMapper.MUSIC_ROOT_ID))
        assertFalse(PodcastAutoMediaMapper.isPodcastId("music_track_t1"))
        assertFalse(PodcastAutoMediaMapper.isPodcastId(AudiobookAutoMediaMapper.AUDIOBOOKS_ROOT_ID))
        assertFalse(PodcastAutoMediaMapper.isPodcastId("audiobooks_book_item1"))
        assertFalse(PodcastAutoMediaMapper.isPodcastId(""))
    }

    /** Cross guard: no podcast ID may be swallowed by the audiobook or music routing. */
    @Test
    fun `podcast node IDs are never audiobook or music IDs`() {
        val ids = listOf(
            PodcastAutoMediaMapper.PODCASTS_ROOT_ID,
            "podcasts_library_lib1",
            "podcasts_show_show1",
            "podcasts_episode_show1|ep1",
        )
        ids.forEach {
            assertFalse("$it must not be an audiobook id", AudiobookAutoMediaMapper.isAudiobookId(it))
            assertFalse("$it must not be a music id", MusicAutoMediaMapper.isMusicId(it))
        }
    }

    // =========================================================================
    // ID round-trip (build/parse)
    // =========================================================================

    @Test
    fun `libraryIdFrom strips the podcasts_library_ prefix`() {
        assertEquals("lib_42", PodcastAutoMediaMapper.libraryIdFrom("podcasts_library_lib_42"))
    }

    @Test
    fun `showIdFrom strips the podcasts_show_ prefix, keeping underscores`() {
        assertEquals("li_abc_123", PodcastAutoMediaMapper.showIdFrom("podcasts_show_li_abc_123"))
    }

    @Test
    fun `episode media id round-trips both ids even when both contain underscores`() {
        // The whole point of the pipe separator: `_` appears in BOTH halves.
        val showId = "li_show_abc_123"
        val episodeId = "ep_episode_xyz_789"
        val mediaId = PodcastAutoMediaMapper.episodeMediaId(showId, episodeId)
        val (parsedShow, parsedEpisode) = PodcastAutoMediaMapper.episodeIdsFrom(mediaId)
        assertEquals(showId, parsedShow)
        assertEquals(episodeId, parsedEpisode)
    }

    @Test
    fun `show node IDs built by podcastsRootChildren round-trip through showIdFrom`() {
        val items = PodcastAutoMediaMapper.podcastsRootChildren(
            podcastLibraries = listOf(library("l1")),
            showsFor = { listOf(show("s_1"), show("s_2")) },
        )
        assertEquals(listOf("s_1", "s_2"), items.map { PodcastAutoMediaMapper.showIdFrom(it.mediaId) })
    }

    @Test
    fun `episode node IDs built by episodeItems round-trip to their show and episode ids`() {
        val items = PodcastAutoMediaMapper.episodeItems(
            showId = "li_show_1",
            showTitle = "Show",
            episodes = listOf(episode("ep_a"), episode("ep_b")),
        )
        assertEquals(
            listOf("li_show_1" to "ep_a", "li_show_1" to "ep_b"),
            items.map { PodcastAutoMediaMapper.episodeIdsFrom(it.mediaId) },
        )
    }

    // =========================================================================
    // podcastsRootItem
    // =========================================================================

    @Test
    fun `podcastsRootItem has correct ID, is browsable, is not playable`() {
        val item = PodcastAutoMediaMapper.podcastsRootItem()
        assertEquals(PodcastAutoMediaMapper.PODCASTS_ROOT_ID, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable ?: false)
        assertFalse(item.mediaMetadata.isPlayable ?: true)
        assertEquals("Podcasts", item.mediaMetadata.title?.toString())
    }

    // =========================================================================
    // podcastsRootChildren — library-picker vs flat-list decision
    // =========================================================================

    @Test
    fun `more than one library produces a browsable picker node per library`() {
        var showsForCalled = false
        val items = PodcastAutoMediaMapper.podcastsRootChildren(
            podcastLibraries = listOf(library("l1", "News"), library("l2", "Tech")),
            showsFor = { showsForCalled = true; emptyList() },
        )
        assertEquals(2, items.size)
        assertEquals("podcasts_library_l1", items[0].mediaId)
        assertEquals("podcasts_library_l2", items[1].mediaId)
        assertEquals("News", items[0].mediaMetadata.title?.toString())
        items.forEach {
            assertTrue(it.mediaMetadata.isBrowsable ?: false)
            assertFalse(it.mediaMetadata.isPlayable ?: true)
        }
        assertFalse("picker level must not resolve shows", showsForCalled)
    }

    @Test
    fun `exactly one library goes straight to the browsable show list`() {
        val items = PodcastAutoMediaMapper.podcastsRootChildren(
            podcastLibraries = listOf(library("l1")),
            showsFor = { libraryId ->
                assertEquals("l1", libraryId)
                listOf(show("s1"), show("s2"))
            },
        )
        assertEquals(2, items.size)
        assertEquals("podcasts_show_s1", items[0].mediaId)
        items.forEach {
            assertTrue(it.mediaMetadata.isBrowsable ?: false)
            assertFalse(it.mediaMetadata.isPlayable ?: true)
        }
    }

    @Test
    fun `single library with unfetched shows returns empty children`() {
        val items = PodcastAutoMediaMapper.podcastsRootChildren(
            podcastLibraries = listOf(library("l1")),
            showsFor = { null },
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `no podcast libraries returns empty children`() {
        val items = PodcastAutoMediaMapper.podcastsRootChildren(
            podcastLibraries = emptyList(),
            showsFor = { listOf(show("s1")) },
        )
        assertEquals(0, items.size)
    }

    // =========================================================================
    // showItems — browsable rows with author subtitle + cover art
    // =========================================================================

    @Test
    fun `showItems maps title and author subtitle`() {
        val item = PodcastAutoMediaMapper.showItems(listOf(show("s1", title = "Daily", author = "NPR")))[0]
        assertEquals("Daily", item.mediaMetadata.title?.toString())
        assertEquals("NPR", item.mediaMetadata.subtitle?.toString())
    }

    @Test
    fun `showItems leaves subtitle null without an author`() {
        val item = PodcastAutoMediaMapper.showItems(listOf(show("s1", author = null)))[0]
        assertNull(item.mediaMetadata.subtitle)
    }

    @Test
    fun `showItems resolves cover art from the show id`() {
        val item = PodcastAutoMediaMapper.showItems(listOf(show("s1"))) { id ->
            Uri.parse("https://abs.example/api/items/$id/cover?token=t")
        }[0]
        assertEquals(
            Uri.parse("https://abs.example/api/items/s1/cover?token=t"),
            item.mediaMetadata.artworkUri,
        )
    }

    // =========================================================================
    // episodeItems — playable rows with subtitle, show artist, cover
    // =========================================================================

    @Test
    fun `episodeItems maps title and falls back to Episode`() {
        val items = PodcastAutoMediaMapper.episodeItems(
            "s1", "Show",
            listOf(episode("e1", title = "Pilot"), episode("e2", title = null)),
        )
        assertEquals("Pilot", items[0].mediaMetadata.title?.toString())
        assertEquals("Episode", items[1].mediaMetadata.title?.toString())
    }

    @Test
    fun `episodeItems are playable, not browsable, with the show title as artist`() {
        val item = PodcastAutoMediaMapper.episodeItems("s1", "The Show", listOf(episode("e1")))[0]
        assertTrue(item.mediaMetadata.isPlayable ?: false)
        assertFalse(item.mediaMetadata.isBrowsable ?: true)
        assertEquals("The Show", item.mediaMetadata.artist?.toString())
    }

    @Test
    fun `episodeItems resolves cover art from the SHOW id, not the episode id`() {
        val item = PodcastAutoMediaMapper.episodeItems("show_1", "Show", listOf(episode("ep_1"))) { id ->
            Uri.parse("https://abs.example/cover/$id")
        }[0]
        assertEquals(Uri.parse("https://abs.example/cover/show_1"), item.mediaMetadata.artworkUri)
    }

    // =========================================================================
    // episodeSubtitle — "pubDate · duration" formatting
    // =========================================================================

    @Test
    fun `episodeSubtitle is duration alone when pubDate is absent`() {
        // 1h 5m = 3900s.
        assertEquals("1h 5m", PodcastAutoMediaMapper.episodeSubtitle(episode("e1", duration = 3900.0)))
    }

    @Test
    fun `episodeSubtitle joins a parsed pubDate and duration with a middle dot`() {
        val subtitle = PodcastAutoMediaMapper.episodeSubtitle(
            episode("e1", pubDate = "Mon, 02 Jan 2006 15:04:05 GMT", duration = 3900.0),
        )
        // Locale-dependent date text, but the join shape and duration are stable.
        assertTrue("expected a joined subtitle, got: $subtitle", subtitle!!.contains(" · "))
        assertTrue(subtitle.endsWith("1h 5m"))
    }

    @Test
    fun `episodeSubtitle is null when neither pubDate nor duration exists`() {
        assertNull(PodcastAutoMediaMapper.episodeSubtitle(episode("e1")))
    }

    @Test
    fun `episodeSubtitle drops an unparseable pubDate, keeping duration`() {
        assertEquals(
            "52m",
            PodcastAutoMediaMapper.episodeSubtitle(episode("e1", pubDate = "garbage", duration = 3120.0)),
        )
    }
}
