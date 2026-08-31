package com.adagiostream.android.service.account

import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.CustomPlaylistEntry
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.SXMMetadataService
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.ui.screens.settings.SxmGroupEditorState
import com.adagiostream.android.ui.screens.settings.buildSxmGroupEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CustomPlaylistSxmIntegrationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val files = listOf("settings.json", "custom_playlists.json", "accounts_v2.enc")

    @Before
    fun setUp() = files.forEach { File(context.filesDir, it).delete() }

    @After
    fun tearDown() = files.forEach { File(context.filesDir, it).delete() }

    @Test
    fun `real custom group operations persist reload and never transfer selection on rename`() = runTest {
        val persistence = PersistenceService(context, json, isUpgradeInstall = { false })
        persistence.saveSettings(AppSettings(sxmChannelGroups = setOf("Custom SXM")))
        val customManager = CustomPlaylistManager(persistence)
        val metadata = mockk<SXMMetadataService>(relaxed = true)
        val espn = mockk<ESPNScoreService>(relaxed = true) {
            every { gamesByChannel } returns MutableStateFlow(emptyMap())
        }
        val manager = AccountManager(persistence, okhttp3.OkHttpClient(), metadata, espn, customManager)
        manager.awaitInitialLoad()
        runCurrent()

        val playlist = customManager.createPlaylist("Local")
        val group = requireNotNull(customManager.addGroup("Custom SXM", playlist.id))
        customManager.addEntry(
            CustomPlaylistEntry(name = "The Highway", streamURL = "https://stream.example/live"),
            group.id,
            playlist.id,
        )
        runCurrent()

        var inventory = manager.rawChannelGroupInventory.value as RawChannelGroupInventory.Complete
        assertEquals(1, inventory.groupCounts["Custom SXM"])
        verify { metadata.matchChannels(match { it.any { channel -> channel.group == "Custom SXM" } }, setOf("Custom SXM"), any()) }

        customManager.renameGroup(group.id, "Renamed", playlist.id)
        runCurrent()

        inventory = manager.rawChannelGroupInventory.value as RawChannelGroupInventory.Complete
        assertFalse(inventory.groupCounts.containsKey("Custom SXM"))
        assertEquals(1, inventory.groupCounts["Renamed"])
        assertEquals(setOf("Custom SXM"), manager.sxmChannelGroups.value)
        val editor = buildSxmGroupEditorState(inventory, manager.sxmChannelGroups.value) as SxmGroupEditorState.Groups
        assertEquals(listOf("Custom SXM"), editor.unavailable.map { it.name })

        val reloadedManager = CustomPlaylistManager(persistence)
        runCurrent()
        assertEquals("Renamed", reloadedManager.playlists.value.single().groups.single().name)

        customManager.deleteGroup(group.id, playlist.id)
        runCurrent()
        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.NoAccounts)
        assertEquals(setOf("Custom SXM"), manager.sxmChannelGroups.value)
        val afterDelete = buildSxmGroupEditorState(
            manager.rawChannelGroupInventory.value,
            manager.sxmChannelGroups.value,
        ) as SxmGroupEditorState.Groups
        assertEquals(listOf("Custom SXM"), afterDelete.unavailable.map { it.name })
    }
}
