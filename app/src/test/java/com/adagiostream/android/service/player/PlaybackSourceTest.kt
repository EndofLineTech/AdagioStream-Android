package com.adagiostream.android.service.player

import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the unified now-playing surface ([NowPlayingItem]) and the
 * [PlaybackSource] discriminator (baw.3.7).
 */
class PlaybackSourceTest {

    // ---- Channel adapter --------------------------------------------------

    @Test
    fun `channel maps to a live now-playing item`() {
        val channel = TestFixtures.makeChannel(
            id = "ch-1",
            name = "Live FM",
            group = "Rock",
            logoURL = "http://logo/ch1.png",
        )
        val item = channel.asNowPlayingItem()

        assertEquals("Live FM", item.displayTitle)
        assertEquals("Rock", item.displaySubtitle)
        assertEquals("http://logo/ch1.png", item.artworkUrl)
        assertTrue(item.isLiveStream)
    }

    @Test
    fun `channel with blank group has null subtitle`() {
        val item = TestFixtures.makeChannel(group = "").asNowPlayingItem()
        assertNull(item.displaySubtitle)
    }

    // ---- Track adapter ----------------------------------------------------

    @Test
    fun `track maps to a non-live now-playing item`() {
        val track = TestFixtures.makeTrack(title = "Song", artist = "Band")
        val item = track.asNowPlayingItem()

        assertEquals("Song", item.displayTitle)
        assertEquals("Band", item.displaySubtitle)
        // E3 foundation: track artwork URL resolved later (baw.3.2).
        assertNull(item.artworkUrl)
        assertFalse(item.isLiveStream)
    }

    @Test
    fun `track with null artist has null subtitle (never leaks artistId)`() {
        val item = TestFixtures.makeTrack(artist = null).asNowPlayingItem()
        assertNull(item.displaySubtitle)
    }

    // ---- PlaybackSource.currentItem --------------------------------------

    @Test
    fun `radio source currentItem is the channel`() {
        val channel = TestFixtures.makeChannel(name = "Live FM")
        val item = PlaybackSource.Radio(channel).currentItem

        assertTrue(item.isLiveStream)
        assertEquals("Live FM", item.displayTitle)
    }

    @Test
    fun `library source currentItem is the track at the active index`() {
        val tracks = TestFixtures.makeTracks(3)
        val source = PlaybackSource.Library(queue = tracks, index = 1)

        assertEquals(tracks[1], source.currentTrack)
        assertEquals("Track 1", source.currentItem.displayTitle)
        assertFalse(source.currentItem.isLiveStream)
    }
}
