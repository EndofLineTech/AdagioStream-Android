package com.adagiostream.android.service.player

import com.adagiostream.android.service.download.DownloadLocator
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wiring tests for the library playback engine (baw.3.2 / baw.3.3 / baw.3.5 / baw.3.8 / baw.5.1).
 *
 * The coordinator is the unit-testable seam between [MusicQueueManager] and the
 * native player: a [FakeLibraryTrackPlayer] records what would be played, and a
 * real (network-free) [NavidromeApi] exercises actual stream/cover-art URL
 * building. This is how the queue-advance, auto-advance, repeat-one, manual
 * next/previous and shuffle/repeat routing get verified without a device.
 *
 * Scrobble tests (baw.5.1) use a MockK [NavidromeApi] with an [UnconfinedTestDispatcher]
 * injected into [MusicPlaybackCoordinator.scope] so fire-and-forget coroutines run
 * eagerly and can be verified synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlaybackCoordinatorTest {

    /** Records the source of each playLibraryTrack call + whether stop() was hit. */
    private class FakeLibraryTrackPlayer : LibraryTrackPlayer {
        val played = mutableListOf<Triple<String, PlaybackSource.Library, Long>>()
        var stopCount = 0

        override fun playLibraryTrack(streamUrl: String, source: PlaybackSource.Library, startPositionMs: Long) {
            played += Triple(streamUrl, source, startPositionMs)
        }

        override fun stop() {
            stopCount++
        }

        val lastSource: PlaybackSource.Library? get() = played.lastOrNull()?.second
        val lastTrackId: String? get() = lastSource?.currentTrack?.id
        val lastStreamUrl: String? get() = played.lastOrNull()?.first
        val lastStartPositionMs: Long? get() = played.lastOrNull()?.third
    }

    private lateinit var queue: MusicQueueManager
    private lateinit var fakePlayer: FakeLibraryTrackPlayer
    private lateinit var coordinator: MusicPlaybackCoordinator
    private lateinit var api: NavidromeApi

    /**
     * Unconfined dispatcher so coroutines launched in [MusicPlaybackCoordinator.scope]
     * run eagerly (synchronously) during tests — no need for [advanceUntilIdle].
     */
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        queue = MusicQueueManager()
        fakePlayer = FakeLibraryTrackPlayer()
        coordinator = MusicPlaybackCoordinator(queue, fakePlayer)
        // Inject a test-controlled scope so fire-and-forget coroutines run eagerly.
        coordinator.scope = testScope
        api = NavidromeApi(
            client = OkHttpClient(),
            host = "https://music.example.com",
            username = "alice",
            password = "sesame",
        )
    }

    /**
     * Builds a [NavidromeApi] mock that:
     *  - returns a valid stream URL from [streamUrl] so [playCurrent] doesn't short-circuit
     *  - returns null from [getCoverArtUrl] (artwork not needed for scrobble tests)
     *  - accepts [scrobble] calls without error
     */
    private fun makeMockApi(): NavidromeApi {
        val mock = mockk<NavidromeApi>()
        every { mock.streamUrl(any(), any(), any()) } returns
            "https://music.example.com/rest/stream.view?id=x".toHttpUrl()
        every { mock.getCoverArtUrl(any(), any()) } returns null
        coEvery { mock.scrobble(any(), any()) } just Runs
        return mock
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
    fun `playAlbum with no startPositionMs plays from the beginning`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api)

        assertEquals(0L, fakePlayer.lastStartPositionMs)
    }

    @Test
    fun `playAlbum forwards startPositionMs to the player for resume-at-position`() {
        // baw.10: onPlaybackResumption rebuilds a paused library track via
        // playAlbum with a saved position — verify it reaches the player so
        // VLCPlayerWrapper can apply it as a start-time media option.
        coordinator.playAlbum(tracks(3), startIndex = 1, api = api, startPositionMs = 45_000L)

        assertEquals("track-1", fakePlayer.lastTrackId)
        assertEquals(45_000L, fakePlayer.lastStartPositionMs)
    }

    @Test
    fun `onTrackEnded auto-advance never carries over a stale startPositionMs`() {
        coordinator.playAlbum(tracks(3), startIndex = 0, api = api, startPositionMs = 45_000L)

        coordinator.onTrackEnded()

        // The NEXT track (auto-advance) must start at 0, not repeat the resumed offset.
        assertEquals(0L, fakePlayer.lastStartPositionMs)
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

    // ---- scrobble (baw.5.1) ----------------------------------------------

    @Test
    fun `scrobble fires now-playing on track start`() {
        val mockApi = makeMockApi()
        coordinator.playAlbum(tracks(1), startIndex = 0, api = mockApi)

        // submission=false = now-playing notification fired when playAlbum starts.
        coVerify(exactly = 1) { mockApi.scrobble("track-0", submission = false) }
    }

    @Test
    fun `scrobble fires submission exactly once at threshold`() {
        val mockApi = makeMockApi()
        coordinator.playAlbum(tracks(1), startIndex = 0, api = mockApi)

        // TestFixtures.makeTracks(1) → track-0 with duration=180 s.
        // 50% threshold = 90 s.
        coordinator.reportPlaybackProgress(89L)  // just under → no submission
        coordinator.reportPlaybackProgress(90L)  // exactly at 50% → fires!
        coordinator.reportPlaybackProgress(150L) // past threshold → guard prevents second fire

        coVerify(exactly = 1) { mockApi.scrobble("track-0", submission = true) }
    }

    // ---- local-first playback (baw.6.3) ----------------------------------

    @Test
    fun `playCurrent loads the local file uri when the track is downloaded`() {
        // Locator reports track-0 as downloaded to disk.
        val locator = DownloadLocator { id ->
            if (id == "track-0") "/data/music/downloads/track-0.mp3" else null
        }
        val localCoordinator = MusicPlaybackCoordinator(queue, fakePlayer, locator)
        localCoordinator.scope = testScope

        localCoordinator.playAlbum(tracks(2), startIndex = 0, api = api)

        val url = fakePlayer.lastStreamUrl!!
        assertTrue("expected a file:// uri but was $url", url.startsWith("file:"))
        assertTrue(url.endsWith("track-0.mp3"))
    }

    @Test
    fun `playCurrent streams when the track is not downloaded`() {
        val locator = DownloadLocator { null }
        val localCoordinator = MusicPlaybackCoordinator(queue, fakePlayer, locator)
        localCoordinator.scope = testScope

        localCoordinator.playAlbum(tracks(1), startIndex = 0, api = api)

        val url = fakePlayer.lastStreamUrl!!
        assertTrue(url.contains("/rest/stream.view"))
    }

    @Test
    fun `scrobble does not fire when no library session is active`() {
        val mockApi = makeMockApi()
        // No playAlbum call → api field is null → reportPlaybackProgress is a no-op.
        coordinator.reportPlaybackProgress(300L)

        coVerify(exactly = 0) { mockApi.scrobble(any(), any()) }
    }
}
