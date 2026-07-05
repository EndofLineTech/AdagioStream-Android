package com.adagiostream.android.ui.screens.nowplaying

import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import com.adagiostream.android.service.player.MusicQueueManager
import com.adagiostream.android.service.player.PlaybackSource
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.testutil.TestFixtures
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Seam test for the shuffle-domain reorder blocker (baw.16).
 *
 * Drives the REAL [MusicQueueManager] → [MusicPlaybackCoordinator] →
 * [NowPlayingViewModel] chain with only [VLCPlayerWrapper] faked (mirrors
 * [NowPlayingViewModelTest]'s `playbackSource` fake, but wires it to a real
 * coordinator instead of a mocked one). A regression that let a canonical-domain
 * drag reach [MusicQueueManager.moveItem] while shuffling would corrupt the
 * canonical queue here — a unit test on [MusicQueueManager] or
 * [MusicPlaybackCoordinator] alone couldn't catch that, since the bug lives in
 * how the ViewModel/UI wire the two together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelShuffleSeamTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playbackSourceFlow = MutableStateFlow<PlaybackSource?>(null)

    /** Fakes only the native player boundary; feeds [PlaybackSource] updates from the real coordinator. */
    private val vlcPlayer = mockk<VLCPlayerWrapper>(relaxed = true) {
        // The ViewModel's init block collects currentChannel unconditionally (radio
        // path) — must be a real Flow, not the relaxed default, or collect() throws.
        every { currentChannel } returns MutableStateFlow(null)
        every { playbackSource } returns playbackSourceFlow
        every { playLibraryTrack(any(), any()) } answers {
            playbackSourceFlow.value = secondArg()
        }
        every { updateLibrarySource(any()) } answers {
            if (playbackSourceFlow.value is PlaybackSource.Library) {
                playbackSourceFlow.value = firstArg()
            }
        }
    }

    private val accountManager = mockk<AccountManager>(relaxed = true)
    private val castManager = mockk<CastManager>(relaxed = true)

    // Network-free — only its pure stream/cover-art URL builders are exercised.
    private val api = NavidromeApi(
        client = OkHttpClient(),
        host = "https://music.example.com",
        username = "alice",
        password = "sesame",
    )

    @Test
    fun `moveQueueItem leaves the canonical queue untouched while shuffle is on`() = runTest {
        val queue = MusicQueueManager()
        val coordinator = MusicPlaybackCoordinator(queue, vlcPlayer)
        val vm = NowPlayingViewModel(vlcPlayer, accountManager, castManager, coordinator)

        val tracks = TestFixtures.makeTracks(5)
        coordinator.setShuffle(true)
        coordinator.playAlbum(tracks, startIndex = 0, api = api)

        val job = backgroundScope.launch(mainDispatcherRule.dispatcher) {
            vm.libraryQueue.collect {}
        }
        advanceUntilIdle()

        assertTrue("shuffle should be reflected through to the ViewModel", vm.isShuffleEnabled.value)
        assertEquals(tracks, vm.libraryQueue.value) // sheet still shows canonical order

        vm.moveQueueItem(from = 0, to = 2)
        advanceUntilIdle()

        // Canonical queue is untouched — a canonical-domain move must not land
        // on MusicQueueManager.moveItem's shuffle-order domain.
        assertEquals(tracks, queue.queue)
        // The Up Next sheet mirrors the canonical queue and must agree.
        assertEquals(tracks, vm.libraryQueue.value)

        job.cancel()
    }
}
