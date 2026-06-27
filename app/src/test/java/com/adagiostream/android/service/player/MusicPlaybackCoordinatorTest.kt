package com.adagiostream.android.service.player

import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.testutil.TestFixtures
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wiring tests for the library playback engine (baw.3.2 / baw.3.3 / baw.3.5 / baw.3.8).
 *
 * The coordinator is the unit-testable seam between [MusicQueueManager] and the
 * native player: a [FakeLibraryTrackPlayer] records what would be played, and a
 * real (network-free) [NavidromeApi] exercises actual stream/cover-art URL
 * building. This is how the queue-advance, auto-advance, repeat-one, manual
 * next/previous and shuffle/repeat routing get verified without a device.
 */
class MusicPlaybackCoordinatorTest {

    /** Records the source of each playLibraryTrack call + whether stop() was hit. */
    private class FakeLibraryTrackPlayer : LibraryTrackPlayer {
        val played = mutableListOf<Pair<String, PlaybackSource.Library>>()
        var stopCount = 0

        override fun playLibraryTrack(streamUrl: String, source: PlaybackSource.Library) {
            played += streamUrl to source
        }

        override fun stop() {
            stopCount++
        }

        val lastSource: PlaybackSource.Library? get() = played.lastOrNull()?.second
        val lastTrackId: String? get() = lastSource?.currentTrack?.id
        val lastStreamUrl: String? get() = played.lastOrNull()?.first
    }

    private lateinit var queue: MusicQueueManager
    private lateinit var fakePlayer: FakeLibraryTrackPlayer
    private lateinit var coordinator: MusicPlaybackCoordinator
    private lateinit var api: NavidromeApi

    @Before
    fun setUp() {
        queue = MusicQueueManager()
        fakePlayer = FakeLibraryTrackPlayer()
        coordinator = MusicPlaybackCoordinator(queue, fakePlayer)
        api = NavidromeApi(
            client = OkHttpClient(),
            host = "https://music.example.com",
            username = "alice",
            password = "sesame",
        )
    }

    private fun tracks(count: Int): List<Track> = TestFixtures.makeTracks(count)

    // ---- playAlbum (baw.3.2 / baw.3.8) -----------------------------------

    @Test
    fun `playAlbum loads the whole album and starts from the tapped index`() {
        val list = tracks(5)
        coordinator.playAlbum(list, startIndex = 2, api = api)

        assertEquals(list, queue.queue)
        assertEquals(2, queue.currentIndex)
        assertEquals("track-2", fakePlayer.lastTrackId)
        // The source carries the full queue so the session player can build next/prev.
        assertEquals(5, fakePlayer.lastSource!!.queue.size)
        assertEquals(2, fakePlayer.lastSource!!.index)
    }

    @Test
    fun `playAlbum builds an authenticated stream url for the started track`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api)

        val url = fakePlayer.lastStreamUrl!!.toHttpUrl()
        assertEquals("/rest/stream.view", url.encodedPath)
        assertEquals("track-0", url.queryParameter("id"))
        assertEquals("alice", url.queryParameter("u"))
        assertTrue("stream url must carry an auth token", url.queryParameter("t") != null)
    }

    @Test
    fun `playAlbum resolves an authenticated cover-art url for the now-playing track`() {
        coordinator.playAlbum(tracks(1), startIndex = 0, api = api)

        val artwork = coordinator.nowPlayingArtworkUrl.value
        assertTrue("artwork should resolve from track.coverArt", artwork != null)
        val url = artwork!!.toHttpUrl()
        assertEquals("/rest/getCoverArt.view", url.encodedPath)
        assertEquals("cover-1", url.queryParameter("id"))
    }

    @Test
    fun `now-playing artwork is null when the track has no cover art`() {
        val noArt = listOf(TestFixtures.makeTrack(id = "n1", coverArt = null))
        coordinator.playAlbum(noArt, startIndex = 0, api = api)

        assertNull(coordinator.nowPlayingArtworkUrl.value)
    }

    // ---- auto-advance (baw.3.3) ------------------------------------------

    @Test
    fun `onTrackEnded advances to the next queued track`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api)

        coordinator.onTrackEnded()

        assertEquals(1, queue.currentIndex)
        assertEquals("track-1", fakePlayer.lastTrackId)
    }

    @Test
    fun `onTrackEnded at the end with repeat off stops playback`() {
        coordinator.playAlbum(tracks(2), startIndex = 1, api = api) // start on last
        val playsBefore = fakePlayer.played.size

        coordinator.onTrackEnded()

        assertEquals(0, fakePlayer.played.size - playsBefore) // no new track played
        assertEquals(1, fakePlayer.stopCount)
        assertNull(coordinator.nowPlayingArtworkUrl.value)
    }

    @Test
    fun `onTrackEnded with repeat all wraps to the first track`() {
        coordinator.playAlbum(tracks(2), startIndex = 1, api = api)
        coordinator.setRepeatMode(RepeatMode.All)

        coordinator.onTrackEnded()

        assertEquals(0, queue.currentIndex)
        assertEquals("track-0", fakePlayer.lastTrackId)
        assertEquals(0, fakePlayer.stopCount)
    }

    @Test
    fun `onTrackEnded with repeat one restarts the same track`() {
        coordinator.playAlbum(tracks(3), startIndex = 1, api = api)
        coordinator.setRepeatMode(RepeatMode.One)
        val playsBefore = fakePlayer.played.size

        coordinator.onTrackEnded()

        // Same index replayed, queue not advanced.
        assertEquals(1, queue.currentIndex)
        assertEquals("track-1", fakePlayer.lastTrackId)
        assertEquals(1, fakePlayer.played.size - playsBefore)
        assertEquals(0, fakePlayer.stopCount)
    }

    // ---- manual navigation (baw.3.4) -------------------------------------

    @Test
    fun `next plays the following track`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api)

        coordinator.next()

        assertEquals(1, queue.currentIndex)
        assertEquals("track-1", fakePlayer.lastTrackId)
    }

    @Test
    fun `previous restarts the current track at the start of the queue`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api)
        val playsBefore = fakePlayer.played.size

        coordinator.previous()

        // At index 0 with repeat off, previous restarts the current track.
        assertEquals(0, queue.currentIndex)
        assertEquals("track-0", fakePlayer.lastTrackId)
        assertEquals(1, fakePlayer.played.size - playsBefore)
    }

    @Test
    fun `previous steps back to the prior track mid-queue`() {
        coordinator.playAlbum(tracks(3), startIndex = 2, api = api)

        coordinator.previous()

        assertEquals(1, queue.currentIndex)
        assertEquals("track-1", fakePlayer.lastTrackId)
    }

    @Test
    fun `playIndex jumps straight to the given queue position`() {
        coordinator.playAlbum(tracks(5), startIndex = 0, api = api)

        coordinator.playIndex(3)

        assertEquals(3, queue.currentIndex)
        assertEquals("track-3", fakePlayer.lastTrackId)
    }

    @Test
    fun `playIndex out of range is a no-op`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api)
        val playsBefore = fakePlayer.played.size

        coordinator.playIndex(99)

        assertEquals(0, fakePlayer.played.size - playsBefore)
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun `next and previous are no-ops on an empty queue`() {
        coordinator.next()
        coordinator.previous()

        assertTrue(fakePlayer.played.isEmpty())
    }

    // ---- shuffle / repeat passthrough (baw.3.5) --------------------------

    @Test
    fun `setShuffle toggles the queue shuffle flag`() {
        coordinator.playAlbum(tracks(5), startIndex = 0, api = api)

        coordinator.setShuffle(true)
        assertTrue(queue.shuffleEnabled)

        coordinator.setShuffle(false)
        assertTrue(!queue.shuffleEnabled)
    }

    @Test
    fun `setRepeatMode updates the queue repeat mode`() {
        coordinator.setRepeatMode(RepeatMode.All)
        assertEquals(RepeatMode.All, queue.repeatMode)
    }
}
