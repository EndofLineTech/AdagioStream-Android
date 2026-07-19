package com.adagiostream.android.ui.screens.channels

import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Expand-state mutation guard (beads_adagio-59p.4.1): while a search is
 * active every section renders expanded regardless of stored state, so
 * toggle / Expand All / Collapse All must all be no-ops — otherwise
 * Collapse All would invisibly wipe the persisted set and Expand All would
 * persist only the search-filtered subset of groups.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val savedSettings = mutableListOf<AppSettings>()

    private fun buildViewModel(): ChannelsViewModel {
        val accountManager = mockk<AccountManager> {
            every { groups } returns MutableStateFlow(
                listOf(
                    ChannelGroup(name = "Rock", channels = listOf(Channel(id = "1", name = "Rock One", streamURL = "http://r", group = "Rock"))),
                    ChannelGroup(name = "Jazz", channels = listOf(Channel(id = "2", name = "Jazz One", streamURL = "http://j", group = "Jazz"))),
                )
            )
            every { favoriteKeys } returns MutableStateFlow(emptySet())
            every { isLoading } returns MutableStateFlow(false)
            every { error } returns MutableStateFlow(null)
            every { feedMetadata } returns MutableStateFlow(emptyMap())
            every { espnGames } returns MutableStateFlow(emptyMap())
            every { epgEntries } returns MutableStateFlow(emptyMap())
        }
        val playlistManager = mockk<CustomPlaylistManager> {
            every { playlists } returns MutableStateFlow(emptyList())
        }
        val persistenceService = mockk<PersistenceService> {
            coEvery { loadSettings() } returns AppSettings()
            coEvery { saveSettings(capture(savedSettings)) } returns Unit
        }
        return ChannelsViewModel(
            accountManager = accountManager,
            vlcPlayer = mockk<VLCPlayerWrapper>(relaxed = true),
            playlistManager = playlistManager,
            persistenceService = persistenceService,
        )
    }

    @Test
    fun `toggle expands, persists, and round-trips back`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.toggleGroupExpanded("Rock")
        advanceUntilIdle()
        assertEquals(setOf("Rock"), viewModel.expandedGroups.value)
        assertEquals(setOf("Rock"), savedSettings.last().expandedGroups)

        viewModel.toggleGroupExpanded("Rock")
        advanceUntilIdle()
        assertTrue(viewModel.expandedGroups.value.isEmpty())
        assertTrue(savedSettings.last().expandedGroups.isEmpty())
    }

    @Test
    fun `toggle, expandAll, and collapseAll are no-ops during an active search`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.toggleGroupExpanded("Rock")
        advanceUntilIdle()
        val before = viewModel.expandedGroups.value
        val savedCount = savedSettings.size

        viewModel.updateSearch("jazz")
        viewModel.toggleGroupExpanded("Jazz")
        viewModel.expandAllGroups()
        viewModel.collapseAllGroups()
        advanceUntilIdle()

        assertEquals(before, viewModel.expandedGroups.value)
        assertEquals(savedCount, savedSettings.size)
    }

    @Test
    fun `expandAll after clearing search covers every group plus the favorites sentinel`() = runTest {
        val viewModel = buildViewModel()
        // filteredGroups is stateIn(WhileSubscribed) — needs a live collector
        // before .value reflects the combine.
        backgroundScope.launch { viewModel.filteredGroups.collect {} }
        advanceUntilIdle()

        viewModel.expandAllGroups()
        advanceUntilIdle()

        assertEquals(setOf(FAVORITES_EXPAND_KEY, "Rock", "Jazz"), viewModel.expandedGroups.value)
        assertEquals(setOf(FAVORITES_EXPAND_KEY, "Rock", "Jazz"), savedSettings.last().expandedGroups)
    }
}
