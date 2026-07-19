package com.adagiostream.android.service.audiobookshelf

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.player.AudiobookFilePlayer
import com.adagiostream.android.service.player.PlaybackSource
import com.adagiostream.android.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Audiobook playback engine tests (beads_adagio-59p.1.5). Deterministic: a
 * fake [AudiobookFilePlayer] renders nothing, the API is a MockK stub, the
 * clock is a mutable variable, and the ~20s sync throttle is driven by calling
 * [AudiobookPlaybackCoordinator.onTick] directly — no wall-clock sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookPlaybackCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records play/seek/rate/stop calls and serves a settable file position. */
    private class FakeAudiobookFilePlayer : AudiobookFilePlayer {
        data class Play(val url: String, val source: PlaybackSource.Audiobook, val startPositionMs: Long, val rate: Float)

        val played = mutableListOf<Play>()
        val seeks = mutableListOf<Long>()
        val rates = mutableListOf<Float>()
        var stopCount = 0
        var positionMs = 0L
        var lastSourceUpdate: PlaybackSource.Audiobook? = null

        override fun playAudiobookFile(streamUrl: String, source: PlaybackSource.Audiobook, startPositionMs: Long, rate: Float) {
            played += Play(streamUrl, source, startPositionMs, rate)
            positionMs = startPositionMs
        }

        override fun updateAudiobookSource(source: PlaybackSource.Audiobook) {
            lastSourceUpdate = source
        }

        override fun seekToPositionMs(positionMs: Long) {
            seeks += positionMs
            this.positionMs = positionMs
        }

        override fun currentPositionMs(): Long = positionMs

        override fun setRate(rate: Float) {
            rates += rate
        }

        override fun stop() {
            stopCount++
        }

        val lastPlay: Play? get() = played.lastOrNull()
    }

    // Three files: [0, 100), [100, 250), [250, 400); chapters [0,90), [90,260), [260,400).
    private fun track(index: Int, startOffset: Double, duration: Double) = AbsAudioTrack(
        index = index, startOffset = startOffset, duration = duration,
        title = "file$index", contentUrl = "/hls/file$index.m4b",
    )

    private val audioTracks = listOf(track(0, 0.0, 100.0), track(1, 100.0, 150.0), track(2, 250.0, 150.0))
    private val chapters = listOf(
        AbsChapter(0, "Chapter 0", 0.0, 90.0),
        AbsChapter(1, "Chapter 1", 90.0, 260.0),
        AbsChapter(2, "Chapter 2", 260.0, 400.0),
    )

    private fun session(id: String = "sess1", currentTime: Double? = 0.0) = AbsPlaybackSession(
        id = id,
        playMethod = 0,
        currentTime = currentTime,
        duration = 400.0,
        chapters = chapters,
        audioTracks = audioTracks,
        displayTitle = "My Book",
        mediaMetadata = AbsMetadata(title = "My Book", authorName = "Author"),
    )

    private val account = Account(
        id = "acct1",
        name = "ABS",
        type = AccountType.Audiobookshelf(
            host = "https://abs.example",
            username = "u",
            accessToken = "at",
            refreshToken = "rt",
        ),
    )

    private lateinit var player: FakeAudiobookFilePlayer
    private lateinit var api: AudiobookshelfApi
    private lateinit var queue: ABSProgressSyncQueue
    private lateinit var playerState: MutableStateFlow<PlaybackState>
    private lateinit var sourceFlow: MutableStateFlow<PlaybackSource?>
    private var nowMs = 0L

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        player = FakeAudiobookFilePlayer()
        queue = ABSProgressSyncQueue(File(tmp.root, "queue.json"))
        playerState = MutableStateFlow(PlaybackState.Playing)
        sourceFlow = MutableStateFlow(null)
        nowMs = 0L
        api = mockk()
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } returns session()
        coEvery { api.syncSession(any(), any(), any(), any()) } returns Unit
        coEvery { api.closeSession(any()) } returns Unit
        coEvery { api.batchUpdateProgress(any()) } returns Unit
        every { api.resolveContentUrl(any()) } answers {
            ("https://abs.example" + firstArg<String>() + "?token=x").toHttpUrl()
        }
        every { api.coverUrl(any(), any(), any()) } returns "https://abs.example/cover".toHttpUrl()
    }

    private fun coordinator(offlineBooks: OfflineAudiobookSource? = null) = AudiobookPlaybackCoordinator(
        player = player,
        queue = queue,
        apiFactory = { _, _, _, _, _ -> api },
        persistenceService = null,
        playerState = playerState,
        playbackSource = sourceFlow,
        offlineBooks = offlineBooks,
        scope = testScope,
        clock = { nowMs },
    )

    // ---- resume precedence (pure) -----------------------------------------

    @Test
    fun `resumePosition applies strict four-level precedence`() {
        val resume = AudiobookPlaybackCoordinator.Companion::resumePosition
        assertEquals(0.0, resume(null, null, null, null), 1e-9)
        assertEquals(4.0, resume(null, null, null, 4.0), 1e-9)
        assertEquals(3.0, resume(null, null, 3.0, 4.0), 1e-9)
        assertEquals(2.0, resume(null, 2.0, 3.0, 4.0), 1e-9)
        assertEquals(1.0, resume(1.0, 2.0, 3.0, 4.0), 1e-9)
    }

    // ---- resume precedence (integration) ----------------------------------

    @Test
    fun `explicit override wins and lands in the right file`() = runTest {
        coordinator().playAudiobook(account, "book1", resumeOverride = 120.0)
        val play = player.lastPlay!!
        assertTrue(play.url.contains("/hls/file1.m4b"))
        assertEquals(20_000L, play.startPositionMs) // 120s global = 20s into file 1
    }

    @Test
    fun `pending offline queue position beats the session currentTime`() = runTest {
        // Flush fails (batch endpoint down) so the queued position survives.
        coEvery { api.batchUpdateProgress(any()) } throws AudiobookshelfApiException.ServerError(503)
        queue.enqueue(AbsProgressUpdate.of("book1", currentTime = 200.0, duration = 400.0, lastUpdate = 1L))
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } returns session(currentTime = 50.0)

        coordinator().playAudiobook(account, "book1")
        val play = player.lastPlay!!
        assertTrue(play.url.contains("/hls/file1.m4b"))
        assertEquals(100_000L, play.startPositionMs) // 200s global = 100s into file 1
    }

    @Test
    fun `session currentTime is used when nothing is queued`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } returns session(currentTime = 50.0)
        coordinator().playAudiobook(account, "book1")
        val play = player.lastPlay!!
        assertTrue(play.url.contains("/hls/file0.m4b"))
        assertEquals(50_000L, play.startPositionMs)
    }

    @Test
    fun `queued progress is flushed before the session opens`() = runTest {
        queue.enqueue(AbsProgressUpdate.of("book1", currentTime = 200.0, duration = 400.0, lastUpdate = 1L))
        coordinator().playAudiobook(account, "book1")
        // Successful flush cleared the queue, so resume fell through to the
        // session currentTime (0.0) — never rewound, the server got 200 first.
        coVerify(exactly = 1) { api.batchUpdateProgress(any()) }
        assertTrue(queue.isEmpty)
        assertEquals(0L, player.lastPlay!!.startPositionMs)
    }

    // ---- sync throttle + timeListened accounting ---------------------------

    @Test
    fun `no sync before 20s, sync fires at 20s with listened delta`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")

        nowMs = 10_000; player.positionMs = 10_000
        c.onTick()
        coVerify(exactly = 0) { api.syncSession(any(), any(), any(), any()) }

        nowMs = 20_000; player.positionMs = 20_000
        c.onTick()
        coVerify(exactly = 1) {
            api.syncSession("sess1", currentTime = 20.0, timeListened = 20.0, duration = 400.0)
        }
    }

    @Test
    fun `forced sync on pause resets the throttle timer`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")

        // Pause at t=10s — forced sync (10s listened).
        nowMs = 10_000; player.positionMs = 10_000
        playerState.value = PlaybackState.Paused
        coVerify(exactly = 1) { api.syncSession("sess1", currentTime = 10.0, timeListened = 10.0, duration = 400.0) }

        // Resume; 15s after the forced sync — still throttled.
        playerState.value = PlaybackState.Playing
        nowMs = 25_000; player.positionMs = 25_000
        c.onTick()
        coVerify(exactly = 1) { api.syncSession(any(), any(), any(), any()) }

        // 20s after the forced sync — next periodic sync, delta since pause.
        nowMs = 30_000; player.positionMs = 30_000
        c.onTick()
        coVerify(exactly = 1) { api.syncSession("sess1", currentTime = 30.0, timeListened = 20.0, duration = 400.0) }
    }

    @Test
    fun `backward seek forces a sync with zero timeListened`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1", resumeOverride = 50.0)
        player.positionMs = 50_000

        c.seekTo(5.0)
        coVerify(exactly = 1) { api.syncSession("sess1", currentTime = 5.0, timeListened = 0.0, duration = 400.0) }
    }

    // ---- seek --------------------------------------------------------------

    @Test
    fun `seek within the current file seeks the player in place`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        c.seekTo(60.0)
        assertEquals(listOf(60_000L), player.seeks)
        assertEquals(1, player.played.size) // no reload
    }

    @Test
    fun `seek across a file boundary loads the target file at its offset`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        c.seekTo(300.0)
        val play = player.lastPlay!!
        assertTrue(play.url.contains("/hls/file2.m4b"))
        assertEquals(50_000L, play.startPositionMs)
        // Position was 0 at seek time — the jumped span is NOT listened time.
        coVerify { api.syncSession("sess1", currentTime = 300.0, timeListened = 0.0, duration = 400.0) }
    }

    // ---- chapter skip ------------------------------------------------------

    @Test
    fun `next and previous route through the chapter timeline with the 3s rule`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1", resumeOverride = 95.0)
        player.positionMs = 0 // 95s global → file0? no: 95 → file0 covers [0,100): offset 95s
        player.positionMs = 95_000

        c.nextChapter() // from 95 → chapter 2 start (260) — crosses into file2
        assertTrue(player.lastPlay!!.url.contains("/hls/file2.m4b"))
        assertEquals(10_000L, player.lastPlay!!.startPositionMs)

        // >3s into chapter 2 → previous restarts chapter 2 (260).
        player.positionMs = 20_000 // 270s global
        c.previousChapter()
        assertEquals(10_000L, player.lastPlay!!.startPositionMs) // 260 → 10s into file2

        // Within 3s of chapter 2's start → previous jumps to chapter 1 (90).
        player.positionMs = 11_000 // 261s global
        c.previousChapter()
        assertTrue(player.lastPlay!!.url.contains("/hls/file0.m4b"))
        assertEquals(90_000L, player.lastPlay!!.startPositionMs)
    }

    // ---- file chaining -----------------------------------------------------

    @Test
    fun `end of file syncs the boundary and chains the next file`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        player.positionMs = 100_000

        c.onFileEnded()
        coVerify { api.syncSession("sess1", currentTime = 100.0, timeListened = 100.0, duration = 400.0) }
        val play = player.lastPlay!!
        assertTrue(play.url.contains("/hls/file1.m4b"))
        assertEquals(0L, play.startPositionMs)
        assertEquals(0, player.stopCount)
    }

    @Test
    fun `end of the last file finishes the book and closes the session`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1", resumeOverride = 390.0)
        player.positionMs = 150_000 // end of file2

        c.onFileEnded()
        coVerify { api.syncSession("sess1", currentTime = 400.0, timeListened = any(), duration = 400.0) }
        coVerify(exactly = 1) { api.closeSession("sess1") }
        assertTrue(player.stopCount > 0)
    }

    // ---- SessionNotFound → reopen -----------------------------------------

    @Test
    fun `SessionNotFound reopens the session and retries the sync at the current position`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } returnsMany
            listOf(session("sess1"), session("sess2"))
        coEvery { api.syncSession("sess1", any(), any(), any()) } throws AudiobookshelfApiException.SessionNotFound
        coEvery { api.syncSession("sess2", any(), any(), any()) } returns Unit

        val c = coordinator()
        c.playAudiobook(account, "book1")
        player.positionMs = 30_000

        c.seekTo(30.0) // forced sync → 404 → reopen → retry
        coVerify(exactly = 2) { api.openPlaybackSession(any(), any(), any(), any()) }
        coVerify(exactly = 1) { api.syncSession("sess2", currentTime = 30.0, timeListened = any(), duration = 400.0) }
        assertTrue(queue.isEmpty) // handled live, nothing queued
    }

    // ---- offline fallback --------------------------------------------------

    @Test
    fun `an unreachable server sends the update to the offline queue`() = runTest {
        coEvery { api.syncSession(any(), any(), any(), any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))

        val c = coordinator()
        c.playAudiobook(account, "book1")
        player.positionMs = 30_000

        c.seekTo(30.0)
        assertEquals(30.0, queue.pendingPosition("book1")!!, 1e-9)
    }

    // ---- cross-source takeover (review B1) --------------------------------

    @Test
    fun `music takeover finalizes the book once at its last observed position`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        nowMs = 5_000; player.positionMs = 42_000
        c.onTick() // last observed audiobook position = 42s

        // Music starts: the wrapper swaps straight to a Library source without
        // ever emitting Idle.
        sourceFlow.value = PlaybackSource.Library(listOf(TestFixtures.makeTrack()), 0)
        coVerify(exactly = 1) { api.syncSession("sess1", currentTime = 42.0, timeListened = any(), duration = 400.0) }
        coVerify(exactly = 1) { api.closeSession("sess1") }
        assertNull(c.nowPlaying.value)

        // The music track's position advances — the coordinator must NEVER
        // read it as audiobook progress again.
        nowMs = 60_000; player.positionMs = 90_000
        c.onTick()
        c.onTick()
        coVerify(exactly = 1) { api.syncSession(any(), any(), any(), any()) }
    }

    // ---- forward seek accounting (review B2) ------------------------------

    @Test
    fun `forward seek reports only pre-seek listened time, never the skipped span`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        player.positionMs = 10_000 // 10s actually listened

        c.seekTo(300.0) // scrub far forward
        coVerify(exactly = 1) {
            api.syncSession("sess1", currentTime = 300.0, timeListened = 10.0, duration = 400.0)
        }
    }

    // ---- offline playback from a downloaded book (bead .1.6) ---------------

    /** Serves one downloaded book; records manifest position writes. */
    private class FakeOfflineSource(private val book: OfflineAudiobook?) : OfflineAudiobookSource {
        val savedPositions = mutableListOf<Pair<String, Double>>()
        override suspend fun completeBook(libraryItemId: String): OfflineAudiobook? = book
        override suspend fun savePosition(libraryItemId: String, seconds: Double) {
            savedPositions += libraryItemId to seconds
        }
    }

    /** Same three-file shape as the streaming session, but with file:// URLs. */
    private fun offlineBook(currentTime: Double = 0.0) = OfflineAudiobook(
        libraryItemId = "book1",
        title = "My Book",
        author = "Author",
        coverPath = "/dl/abs_book1_cover.webp",
        currentTime = currentTime,
        timeline = AudiobookTimeline(
            listOf(
                AbsAudioTrack(0, 0.0, 100.0, null, "file:///dl/f0.m4b"),
                AbsAudioTrack(1, 100.0, 150.0, null, "file:///dl/f1.m4b"),
                AbsAudioTrack(2, 250.0, 150.0, null, "file:///dl/f2.m4b"),
            ),
            chapters,
        ),
    )

    @Test
    fun `online sync updates the manifest position cache`() = runTest {
        // Review B1: hours of ONLINE listening must keep the download
        // manifest's resume position fresh — reboot + airplane mode would
        // otherwise resume at the stale download-time position.
        val offline = FakeOfflineSource(offlineBook())
        val c = coordinator(offline)
        c.playAudiobook(account, "book1") // streaming session opens fine

        nowMs = 20_000; player.positionMs = 20_000
        c.onTick() // periodic ONLINE sync
        coVerify(exactly = 1) { api.syncSession(any(), any(), any(), any()) }
        assertTrue(offline.savedPositions.contains("book1" to 20.0))
    }

    @Test
    fun `session open failure falls back to the complete local copy`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))

        val offline = FakeOfflineSource(offlineBook(currentTime = 50.0))
        coordinator(offline).playAudiobook(account, "book1")

        val play = player.lastPlay!!
        assertEquals("file:///dl/f0.m4b", play.url)
        assertEquals(50_000L, play.startPositionMs) // manifest-cached resume
        assertEquals("My Book", play.source.bookTitle)
        assertEquals(400.0, play.source.totalDurationSeconds, 1e-9)
    }

    @Test
    fun `no session and no complete download does not start playback`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))

        coordinator(FakeOfflineSource(null)).playAudiobook(account, "book1")
        assertNull(player.lastPlay)
    }

    @Test
    fun `offline resume - pending queue position beats the manifest cache`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))
        coEvery { api.batchUpdateProgress(any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))
        queue.enqueue(AbsProgressUpdate.of("book1", currentTime = 200.0, duration = 400.0, lastUpdate = 1L))

        coordinator(FakeOfflineSource(offlineBook(currentTime = 50.0))).playAudiobook(account, "book1")

        val play = player.lastPlay!!
        assertEquals("file:///dl/f1.m4b", play.url) // 200s global = file 1
        assertEquals(100_000L, play.startPositionMs)
    }

    @Test
    fun `offline progress goes only to the queue and the manifest cache`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))

        val offline = FakeOfflineSource(offlineBook())
        val c = coordinator(offline)
        c.playAudiobook(account, "book1")
        player.positionMs = 30_000

        c.seekTo(30.0)
        coVerify(exactly = 0) { api.syncSession(any(), any(), any(), any()) }
        assertEquals(30.0, queue.pendingPosition("book1")!!, 1e-9)
        assertTrue(offline.savedPositions.contains("book1" to 30.0))
    }

    @Test
    fun `offline seek crosses file boundaries through the manifest timeline`() = runTest {
        coEvery { api.openPlaybackSession(any(), any(), any(), any()) } throws
            AudiobookshelfApiException.Unreachable(IOException("no route"))

        val c = coordinator(FakeOfflineSource(offlineBook()))
        c.playAudiobook(account, "book1")
        c.seekTo(300.0)

        val play = player.lastPlay!!
        assertEquals("file:///dl/f2.m4b", play.url)
        assertEquals(50_000L, play.startPositionMs)
    }

    // ---- stop / finalize ---------------------------------------------------

    @Test
    fun `stop syncs the last observed position and closes the session`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        nowMs = 5_000; player.positionMs = 42_000
        c.onTick() // records lastObservedGlobal = 42

        c.stop()
        coVerify { api.syncSession("sess1", currentTime = 42.0, timeListened = any(), duration = 400.0) }
        coVerify(exactly = 1) { api.closeSession("sess1") }
        assertTrue(player.stopCount > 0)
        assertNull(c.nowPlaying.value)
    }

    // ---- speed -------------------------------------------------------------

    @Test
    fun `speed is applied to the player, clamped, and passed to every file load`() = runTest {
        val c = coordinator()
        c.setSpeed(1.5f)
        assertEquals(listOf(1.5f), player.rates)

        c.setSpeed(99f)
        assertEquals(3.0f, c.speed, 0f)

        c.setSpeed(1.5f)
        c.playAudiobook(account, "book1")
        assertEquals(1.5f, player.lastPlay!!.rate, 0f)
    }

    @Test
    fun `cycleSpeed walks the steps and wraps`() = runTest {
        val c = coordinator()
        assertEquals(1.0f, c.speed, 0f)
        c.cycleSpeed()
        assertEquals(1.25f, c.speed, 0f)
        c.setSpeed(3.0f)
        c.cycleSpeed()
        assertEquals(0.75f, c.speed, 0f) // wraps past the top step
    }

    // ---- metadata ----------------------------------------------------------

    @Test
    fun `the published source carries book title, chapter and total duration`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1", resumeOverride = 95.0)
        val source = player.lastPlay!!.source
        assertEquals("My Book", source.bookTitle)
        assertEquals("Author", source.author)
        assertEquals("Chapter 1", source.chapterTitle)
        assertEquals(400.0, source.totalDurationSeconds, 1e-9)
        assertEquals("book1", source.libraryItemId)
    }

    @Test
    fun `crossing a chapter boundary republishes the source with the new chapter`() = runTest {
        val c = coordinator()
        c.playAudiobook(account, "book1")
        player.positionMs = 95_000 // crossed from Chapter 0 into Chapter 1
        c.onTick()
        assertEquals("Chapter 1", player.lastSourceUpdate!!.chapterTitle)
    }
}
