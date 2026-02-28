package com.adagiostream.android.ui.screens.nowplaying

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.ExoPlayerWrapper
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

    private val exoPlayer = mockk<ExoPlayerWrapper>(relaxed = true) {
        every { currentChannel } returns currentChannelFlow
        every { playbackState } returns playbackStateFlow
        every { bitrateKbps } returns bitrateFlow
        every { streamStartedAt } returns streamStartedAtFlow
    }

    private val accountManager = mockk<AccountManager>(relaxed = true) {
        every { epgEntries } returns epgEntriesFlow
    }

    private fun createViewModel(): NowPlayingViewModel {
        return NowPlayingViewModel(exoPlayer, accountManager)
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
    fun `togglePlayPause delegates to ExoPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.togglePlayPause()
        verify { exoPlayer.togglePlayPause() }
    }

    @Test
    fun `stop delegates to ExoPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.stop()
        verify { exoPlayer.stop() }
    }

    @Test
    fun `playNext delegates to ExoPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.playNext()
        verify { exoPlayer.playNext() }
    }

    @Test
    fun `playPrevious delegates to ExoPlayerWrapper`() = runTest {
        val vm = createViewModel()
        vm.playPrevious()
        verify { exoPlayer.playPrevious() }
    }
}
