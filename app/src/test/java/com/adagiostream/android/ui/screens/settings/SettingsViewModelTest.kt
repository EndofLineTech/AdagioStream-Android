package com.adagiostream.android.ui.screens.settings

import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.TextSizeMode
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.persistence.SettingsLoadException
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val updatedSettings = mutableListOf<AppSettings>()
    private val persistenceService = mockk<PersistenceService>(relaxed = true) {
        coEvery { loadSettings() } returns AppSettings()
        coEvery { updateSettings(any()) } coAnswers {
            firstArg<(AppSettings) -> AppSettings>().invoke(AppSettings()).also(updatedSettings::add)
        }
    }
    private val accountManager = mockk<AccountManager>(relaxed = true)
    private val vlcPlayerWrapper = mockk<VLCPlayerWrapper>(relaxed = true)
    private val espnScoreService = mockk<ESPNScoreService>(relaxed = true)
    private val downloadManager = mockk<com.adagiostream.android.service.download.DownloadManager>(relaxed = true)
    private val musicLibraryRepository =
        mockk<com.adagiostream.android.service.library.MusicLibraryRepository>(relaxed = true)

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            persistenceService,
            accountManager,
            vlcPlayerWrapper,
            espnScoreService,
            downloadManager,
            musicLibraryRepository,
        )
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
    fun `updateBufferDuration notifies VLCPlayerWrapper`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(12)
        verify { vlcPlayerWrapper.updateBufferDuration(12) }
    }

    @Test
    fun `updateBufferDuration persists settings`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateBufferDuration(10)
        advanceUntilIdle()
        assertTrue(updatedSettings.any { it.bufferDurationSeconds == 10 })
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
        assertTrue(updatedSettings.any { it.appearanceMode == AppearanceMode.LIGHT })
    }

    @Test
    fun `non-SXM save failure is exposed and can be retried`() = runTest {
        coEvery { persistenceService.updateSettings(any()) } throws
            SettingsLoadException(IllegalArgumentException("corrupt settings"))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateAppearanceMode(AppearanceMode.DARK)
        advanceUntilIdle()

        assertEquals("Could not save settings. The existing settings file was not changed.", vm.settingsSaveError.value)

        coEvery { persistenceService.updateSettings(any()) } coAnswers {
            firstArg<(AppSettings) -> AppSettings>().invoke(AppSettings()).also(updatedSettings::add)
        }
        vm.retrySettingsSave()
        advanceUntilIdle()

        assertNull(vm.settingsSaveError.value)
        assertTrue(updatedSettings.any { it.appearanceMode == AppearanceMode.DARK })
    }

    @Test
    fun `settings save cancellation is not converted to a save error`() = runTest {
        coEvery { persistenceService.updateSettings(any()) } throws CancellationException("screen stopped")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateAppearanceMode(AppearanceMode.DARK)
        advanceUntilIdle()

        assertNull(vm.settingsSaveError.value)
    }

    // --- Offline Mode (baw.12) ---

    @Test
    fun `updateOfflineMode updates settings and persists`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateOfflineMode(true)
        advanceUntilIdle()
        assertEquals(true, vm.settings.value.offlineMode)
        assertTrue(updatedSettings.any { it.offlineMode })

        vm.updateOfflineMode(false)
        advanceUntilIdle()
        assertEquals(false, vm.settings.value.offlineMode)
        assertTrue(updatedSettings.any { !it.offlineMode })
    }

    // --- Tab reorg tip (beads_adagio-15x.4) ---

    @Test
    fun `markTabReorgTipSeen flips the flag and persists`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(false, vm.settings.value.hasSeenTabReorgTip)

        vm.markTabReorgTipSeen()
        advanceUntilIdle()

        assertEquals(true, vm.settings.value.hasSeenTabReorgTip)
        assertTrue(updatedSettings.any { it.hasSeenTabReorgTip })
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

    @Test
    fun `SXM group toggle delegates immediate save to account manager`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSxmChannelGroup("Arbitrary Group")
        advanceUntilIdle()

        verify { accountManager.requestToggleSxmChannelGroup("Arbitrary Group") }
    }

    @Test
    fun `SXM inventory retry delegates provider and custom reload`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.retrySxmChannelGroupInventory()
        advanceUntilIdle()

        coVerify { accountManager.retrySxmChannelGroupInventory() }
    }

    @Test
    fun `data export includes exact SXM group selection in settings`() = runTest {
        val selected = setOf("SiriusXM", "Arbitrary Group")
        val persisted = AppSettings(sxmChannelGroups = selected)
        every { persistenceService.loadSettingsSync() } returns persisted
        coEvery { persistenceService.loadSettings() } returns persisted
        coEvery { persistenceService.loadLovedTracks() } returns emptyList()
        coEvery { persistenceService.loadCustomPlaylists() } returns emptyList()
        every { accountManager.accounts } returns MutableStateFlow(emptyList())
        every { accountManager.channels } returns MutableStateFlow(emptyList())
        val vm = createViewModel()
        advanceUntilIdle()

        vm.exportMyData()
        advanceUntilIdle()

        val root = Json.parseToJsonElement(requireNotNull(vm.exportJson.value)).jsonObject
        val exported = root.getValue("settings").jsonObject
            .getValue("sxmChannelGroups").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertEquals(selected, exported)
    }

    @Test
    fun `data export includes explicit empty SXM group selection`() = runTest {
        val persisted = AppSettings(sxmChannelGroups = emptySet())
        every { persistenceService.loadSettingsSync() } returns persisted
        coEvery { persistenceService.loadSettings() } returns persisted
        coEvery { persistenceService.loadLovedTracks() } returns emptyList()
        coEvery { persistenceService.loadCustomPlaylists() } returns emptyList()
        every { accountManager.accounts } returns MutableStateFlow(emptyList())
        every { accountManager.channels } returns MutableStateFlow(emptyList())
        val vm = createViewModel()
        advanceUntilIdle()

        vm.exportMyData()
        advanceUntilIdle()

        val settings = Json.parseToJsonElement(requireNotNull(vm.exportJson.value))
            .jsonObject.getValue("settings").jsonObject
        assertTrue(settings.containsKey("sxmChannelGroups"))
        assertTrue(settings.getValue("sxmChannelGroups").jsonArray.isEmpty())
    }
}
