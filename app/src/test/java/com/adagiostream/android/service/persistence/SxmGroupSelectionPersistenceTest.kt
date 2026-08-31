package com.adagiostream.android.service.persistence

import com.adagiostream.android.model.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SxmGroupSelectionPersistenceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val settingsFile = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        settingsFile.delete()
    }

    @After
    fun cleanUp() {
        settingsFile.delete()
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

        assertNull(service.loadSettingsSync().sxmChannelGroups)
    }

    @Test
    fun `settings written by an older version remain migration uninitialized`() {
        settingsFile.writeText("""{"setupCompleted":true,"debugLoggingEnabled":true}""")
        val service = PersistenceService(context, json, isUpgradeInstall = { false })

        val settings = service.loadSettingsSync()

        assertNull(settings.sxmChannelGroups)
        assertTrue(settings.debugLoggingEnabled)
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
