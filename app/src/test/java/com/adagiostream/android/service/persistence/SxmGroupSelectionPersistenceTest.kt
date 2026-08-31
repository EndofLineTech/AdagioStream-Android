package com.adagiostream.android.service.persistence

import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.CustomPlaylistGroup
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.account.RawChannelGroupInventory
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.SXMMetadataService
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SxmGroupSelectionPersistenceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val settingsFile = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val installArtifactNames = listOf(
        "accounts_v2.enc",
        "favorites.json",
        "loved_tracks.json",
        "last_played.txt",
        "custom_playlists.json",
    )

    @Before
    fun setUp() {
        settingsFile.delete()
        installArtifactNames.forEach { File(context.filesDir, it).delete() }
    }

    @After
    fun cleanUp() {
        settingsFile.delete()
        installArtifactNames.forEach { File(context.filesDir, it).delete() }
    }

    @Test
    fun `new install is persisted as explicitly empty`() {
        val service = PersistenceService(context, json, isUpgradeInstall = { false })

        val settings = service.loadSettingsSync()

        assertEquals(emptySet<String>(), settings.sxmChannelGroups)
        assertTrue(settingsFile.exists())
        assertNotNull(json.parseToJsonElement(settingsFile.readText()).jsonObject["sxmChannelGroups"])
    }

    @Test
    fun `upgraded install without settings remains migration uninitialized`() {
        val service = PersistenceService(context, json, isUpgradeInstall = { true })

        val result = service.loadSettingsResultSync()
        assertTrue(result is SettingsLoadResult.ExistingInstallWithoutSettings)
        assertNull(result.settingsOrThrow().sxmChannelGroups)
    }

    @Test
    fun `settings written by an older version remain migration uninitialized`() {
        settingsFile.writeText("""{"setupCompleted":true,"debugLoggingEnabled":true}""")
        val service = PersistenceService(context, json, isUpgradeInstall = { false })

        val settings = service.loadSettingsSync()

        assertNull(settings.sxmChannelGroups)
        assertTrue(settings.debugLoggingEnabled)
        assertTrue(service.loadSettingsResultSync() is SettingsLoadResult.MigrationUninitialized)
    }

    @Test
    fun `every existing install artifact keeps missing settings migration uninitialized`() {
        installArtifactNames.forEach { artifactName ->
            settingsFile.delete()
            installArtifactNames.forEach { File(context.filesDir, it).delete() }
            File(context.filesDir, artifactName).writeText("existing")

            val result = PersistenceService(context, json, isUpgradeInstall = { false })
                .loadSettingsResultSync()

            assertTrue("$artifactName must classify as existing install", result is SettingsLoadResult.ExistingInstallWithoutSettings)
            assertNull(result.settingsOrThrow().sxmChannelGroups)
        }
    }

    @Test
    fun `corrupt settings are a failure and routine update preserves the file`() = runTest {
        val corrupt = "{not valid settings"
        settingsFile.writeText(corrupt)
        val service = PersistenceService(context, json, isUpgradeInstall = { true })

        assertTrue(service.loadSettingsResultSync() is SettingsLoadResult.Failure)
        try {
            service.updateSettings { it.copy(sxmChannelGroups = setOf("SiriusXM")) }
            fail("Corrupt settings must prevent an update")
        } catch (_: SettingsLoadException) {
            // Expected: the original bytes remain available for recovery.
        }
        assertEquals(corrupt, settingsFile.readText())
    }

    @Test
    fun `production migration waits for corrupt settings and succeeds after recovery`() = runTest {
        val corrupt = "{not valid settings"
        settingsFile.writeText(corrupt)
        File(context.filesDir, "custom_playlists.json").writeText(
            json.encodeToString(
                listOf(CustomPlaylist(name = "Local", groups = listOf(CustomPlaylistGroup(name = "SiriusXM")))),
            ),
        )
        val service = PersistenceService(context, json, isUpgradeInstall = { true })
        val customManager = CustomPlaylistManager(service)
        val metadata = mockk<SXMMetadataService>(relaxed = true)
        val espn = mockk<ESPNScoreService>(relaxed = true) {
            every { gamesByChannel } returns MutableStateFlow(emptyMap())
        }
        val manager = AccountManager(service, okhttp3.OkHttpClient(), metadata, espn, customManager)

        manager.awaitInitialLoad()
        runCurrent()

        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.Complete)
        assertNull(manager.sxmChannelGroups.value)
        assertEquals(corrupt, settingsFile.readText())

        settingsFile.writeText("""{"setupCompleted":true}""")
        manager.retrySxmSelectionMigration()
        runCurrent()

        assertEquals(setOf("SiriusXM"), manager.sxmChannelGroups.value)
    }

    @Test
    fun `explicit empty and non-empty selections round trip`() = runTest {
        val service = PersistenceService(context, json, isUpgradeInstall = { false })

        service.saveSettings(AppSettings(sxmChannelGroups = emptySet()))
        assertEquals(emptySet<String>(), service.loadSettings().sxmChannelGroups)

        service.saveSettings(AppSettings(sxmChannelGroups = setOf("SiriusXM", "My Group")))
        assertEquals(setOf("SiriusXM", "My Group"), service.loadSettings().sxmChannelGroups)
    }

    @Test
    fun `completed migration is not reclassified on later loads`() = runTest {
        settingsFile.writeText("""{"setupCompleted":true}""")
        val service = PersistenceService(context, json, isUpgradeInstall = { true })
        assertNull(service.loadSettings().sxmChannelGroups)

        service.saveSettings(service.loadSettings().copy(sxmChannelGroups = setOf("SiriusXM")))

        assertEquals(setOf("SiriusXM"), service.loadSettings().sxmChannelGroups)
        assertEquals(setOf("SiriusXM"), PersistenceService(context, json).loadSettingsSync().sxmChannelGroups)
    }

    @Test
    fun `delete all data resets selection to new-install explicit empty`() = runTest {
        val service = PersistenceService(context, json, isUpgradeInstall = { true })
        service.saveSettings(AppSettings(sxmChannelGroups = setOf("SiriusXM")))

        service.deleteAllData()

        assertEquals(emptySet<String>(), service.loadSettings().sxmChannelGroups)
        assertEquals(emptySet<String>(), service.settings.value.sxmChannelGroups)
    }

    @Test
    fun `custom inventory load exposes corruption instead of silently completing migration`() = runTest {
        File(context.filesDir, "custom_playlists.json").apply {
            writeText("not json")
        }
        val service = PersistenceService(context, json, isUpgradeInstall = { true })

        assertTrue(service.loadCustomPlaylistsResult().isFailure)

        File(context.filesDir, "custom_playlists.json").delete()
    }

    @Test
    fun `atomic unrelated settings update preserves explicit selection`() = runTest {
        val service = PersistenceService(context, json, isUpgradeInstall = { false })
        service.saveSettings(AppSettings(sxmChannelGroups = setOf("Satellite")))

        service.updateSettings { it.copy(debugLoggingEnabled = true) }

        assertEquals(setOf("Satellite"), service.loadSettings().sxmChannelGroups)
        assertTrue(service.loadSettings().debugLoggingEnabled)
    }
}
