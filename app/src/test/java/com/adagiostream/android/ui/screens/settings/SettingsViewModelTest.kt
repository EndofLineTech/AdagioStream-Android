package com.adagiostream.android.ui.screens.settings

import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.TextSizeMode
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.ExoPlayerWrapper
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val persistenceService = mockk<PersistenceService>(relaxed = true) {
        coEvery { loadSettings() } returns AppSettings()
    }
    private val accountManager = mockk<AccountManager>(relaxed = true)
    private val exoPlayerWrapper = mockk<ExoPlayerWrapper>(relaxed = true)

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(persistenceService, accountManager, exoPlayerWrapper)
    }

    // --- Buffer Duration ---

    @Test
    fun `updateBufferDuration clamps below minimum`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(2)
        assertEquals(5, vm.settings.value.bufferDurationSeconds)
    }

    @Test
    fun `updateBufferDuration clamps above maximum`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(20)
        assertEquals(15, vm.settings.value.bufferDurationSeconds)
    }

    @Test
    fun `updateBufferDuration accepts value in range`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(8)
        assertEquals(8, vm.settings.value.bufferDurationSeconds)
    }

    @Test
    fun `updateBufferDuration notifies ExoPlayerWrapper`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(12)
        verify { exoPlayerWrapper.updateBufferDuration(12) }
    }

    @Test
    fun `updateBufferDuration persists settings`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(10)
        advanceUntilIdle()
        coVerify { persistenceService.saveSettings(match { it.bufferDurationSeconds == 10 }) }
    }

    // --- Appearance Mode ---

    @Test
    fun `updateAppearanceMode updates settings`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateAppearanceMode(AppearanceMode.DARK)
        assertEquals(AppearanceMode.DARK, vm.settings.value.appearanceMode)
    }

    @Test
    fun `updateAppearanceMode persists`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateAppearanceMode(AppearanceMode.LIGHT)
        advanceUntilIdle()
        coVerify { persistenceService.saveSettings(match { it.appearanceMode == AppearanceMode.LIGHT }) }
    }

    // --- Text Size ---

    @Test
    fun `updateTextSizeMode updates settings`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateTextSizeMode(TextSizeMode.XL)
        assertEquals(TextSizeMode.XL, vm.settings.value.textSizeMode)
    }

    // --- Sort Mode ---

    @Test
    fun `updateSortMode updates settings and notifies AccountManager`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateSortMode(SortMode.NATURAL)
        assertEquals(SortMode.NATURAL, vm.settings.value.sortMode)
        verify { accountManager.updateSortMode(SortMode.NATURAL) }
    }

    @Test
    fun `updateGroupSortMode updates settings and notifies AccountManager`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateGroupSortMode(SortMode.ACCOUNT_ORDER)
        assertEquals(SortMode.ACCOUNT_ORDER, vm.settings.value.groupSortMode)
        verify { accountManager.updateGroupSortMode(SortMode.ACCOUNT_ORDER) }
    }

    // --- Clear Favorites ---

    @Test
    fun `clearAllFavorites delegates to AccountManager`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.clearAllFavorites()
        advanceUntilIdle()
        coVerify { accountManager.clearAllFavorites() }
    }

    // --- Init loads settings ---

    @Test
    fun `init loads persisted settings`() = runTest {
        coEvery { persistenceService.loadSettings() } returns AppSettings(bufferDurationSeconds = 12)
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(12, vm.settings.value.bufferDurationSeconds)
    }
}
