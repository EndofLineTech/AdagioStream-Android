package com.adagiostream.android.ui.screens.nowplaying

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import com.adagiostream.android.service.player.PlaybackSource
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.testutil.TestFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val currentChannelFlow = MutableStateFlow<Channel?>(null)
    private val playbackStateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val bitrateFlow = MutableStateFlow(0f)
    private val streamStartedAtFlow = MutableStateFlow<Long?>(null)
    private val epgEntriesFlow = MutableStateFlow<Map<String, List<EPGEntry>>>(emptyMap())
    private val playbackSourceFlow = MutableStateFlow<PlaybackSource?>(null)

    private val vlcPlayer = mockk<VLCPlayerWrapper>(relaxed = true) {
        every { currentChannel } returns currentChannelFlow
        every { playbackState } returns playbackStateFlow
        every { bitrateKbps } returns bitrateFlow
        every { streamStartedAt } returns streamStartedAtFlow
        every { playbackSource } returns playbackSourceFlow
    }

    private val accountManager = mockk<AccountManager>(relaxed = true) {
        every { epgEntries } returns epgEntriesFlow
    }

    private val castManager = mockk<CastManager>(relaxed = true)
    private val musicPlaybackCoordinator = mockk<MusicPlaybackCoordinator>(relaxed = true)

    private fun createViewModel(): NowPlayingViewModel {
        return NowPlayingViewModel(vlcPlayer, accountManager, castManager, musicPlaybackCoordinator)
    }

    @Test
    fun `currentEPGEntries is empty when no channel`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.currentEPGEntries.value.isEmpty())
    }

    @Test
    fun `currentEPGEntries is empty when channel has no epgChannelID`() = runTest {
        val vm = createViewModel()
        currentChannelFlow.value = TestFixtures.makeChannel(epgChannelID = null)
        advanceUntilIdle()
        assertTrue(vm.currentEPGEntries.value.isEmpty())
    }

    @Test
    fun `currentEPGEntries returns matching entries`() = runTest {
        val entries = listOf(TestFixtures.makeEPGEntry(channelID = "epg1", title = "Live Show"))
        epgEntriesFlow.value = mapOf("epg1" to entries)

        val vm = createViewModel()

        // WhileSubscribed requires an active collector to start the upstream combine
        val job = backgroundScope.launch(mainDispatcherRule.dispatcher) {
            vm.currentEPGEntries.collect {}
        }

        currentChannelFlow.value = TestFixtures.makeChannel(epgChannelID = "epg1")
        advanceUntilIdle()

        assertEquals(1, vm.currentEPGEntries.value.size)
        assertEquals("Live Show", vm.currentEPGEntries.value[0].title)
        job.cancel()
    }

    @Test
    fun `togglePlayPause delegates to VLCPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.togglePlayPause()
        verify { vlcPlayer.togglePlayPause() }
    }

    @Test
    fun `stop delegates to VLCPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.stop()
        verify { vlcPlayer.stop() }
    }

    @Test
    fun `playNext delegates to VLCPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.playNext()
        verify { vlcPlayer.playNext() }
    }

    @Test
    fun `playPrevious delegates to VLCPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.playPrevious()
        verify { vlcPlayer.playPrevious() }
    }

    // ---- library source / Up Next surface (baw.9.3) ------------------------

    @Test
    fun `isLibrarySource is false and libraryQueue is empty for radio`() = runTest {
        val vm = createViewModel()
        playbackSourceFlow.value = PlaybackSource.Radio(TestFixtures.makeChannel())

        val job = backgroundScope.launch(mainDispatcherRule.dispatcher) {
            vm.isLibrarySource.collect {}
        }
        advanceUntilIdle()

        assertTrue(!vm.isLibrarySource.value)
        assertTrue(vm.libraryQueue.value.isEmpty())
        job.cancel()
    }

    @Test
    fun `isLibrarySource is true and libraryQueue exposes the queue when playing library`() = runTest {
        val vm = createViewModel()
        val tracks: List<Track> = TestFixtures.makeTracks(3)
        playbackSourceFlow.value = PlaybackSource.Library(tracks, index = 1)

        val jobs = listOf(
            backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.isLibrarySource.collect {} },
            backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.libraryQueue.collect {} },
            backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.libraryQueueIndex.collect {} },
        )
        advanceUntilIdle()

        assertTrue(vm.isLibrarySource.value)
        assertEquals(tracks, vm.libraryQueue.value)
        assertEquals(1, vm.libraryQueueIndex.value)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `playQueueIndex delegates to MusicPlaybackCoordinator`() = runTest {
        val vm = createViewModel()
        vm.playQueueIndex(2)
        verify { musicPlaybackCoordinator.playIndex(2) }
    }

    @Test
    fun `moveQueueItem delegates to MusicPlaybackCoordinator`() = runTest {
        val vm = createViewModel()
        vm.moveQueueItem(0, 3)
        verify { musicPlaybackCoordinator.moveQueueItem(0, 3) }
    }

    // ---- seek bar (baw.9.5) -------------------------------------------------

    @Test
    fun `libraryDurationMs is null for radio`() = runTest {
        val vm = createViewModel()
        playbackSourceFlow.value = PlaybackSource.Radio(TestFixtures.makeChannel())

        val job = backgroundScope.launch(mainDispatcherRule.dispatcher) {
            vm.libraryDurationMs.collect {}
        }
        advanceUntilIdle()

        assertEquals(null, vm.libraryDurationMs.value)
        job.cancel()
    }

    @Test
    fun `libraryDurationMs converts the track's duration from seconds to milliseconds`() = runTest {
        val vm = createViewModel()
        val track = TestFixtures.makeTrack(duration = 200)
        playbackSourceFlow.value = PlaybackSource.Library(listOf(track), index = 0)

        val job = backgroundScope.launch(mainDispatcherRule.dispatcher) {
            vm.libraryDurationMs.collect {}
        }
        advanceUntilIdle()

        assertEquals(200_000L, vm.libraryDurationMs.value)
        job.cancel()
    }

    @Test
    fun `libraryDurationMs is null when the track has no known duration`() = runTest {
        val vm = createViewModel()
        val track = TestFixtures.makeTrack(duration = null)
        playbackSourceFlow.value = PlaybackSource.Library(listOf(track), index = 0)

        val job = backgroundScope.launch(mainDispatcherRule.dispatcher) {
            vm.libraryDurationMs.collect {}
        }
        advanceUntilIdle()

        assertEquals(null, vm.libraryDurationMs.value)
        job.cancel()
    }

    @Test
    fun `currentPositionMs delegates to VLCPlayerWrapper`() = runTest {
        every { vlcPlayer.currentPositionMs() } returns 42_000L
        val vm = createViewModel()
        assertEquals(42_000L, vm.currentPositionMs())
    }

    @Test
    fun `seekTo delegates to VLCPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.seekTo(15_000L)
        verify { vlcPlayer.seekToPositionMs(15_000L) }
    }
}
