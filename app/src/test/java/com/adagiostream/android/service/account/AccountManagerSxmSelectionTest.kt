package com.adagiostream.android.service.account

import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.CustomPlaylistEntry
import com.adagiostream.android.model.CustomPlaylistGroup
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.SXMMetadataService
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.persistence.SettingsLoadResult
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagerSxmSelectionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val persistence = mockk<PersistenceService>(relaxed = true)
    private val metadataService = mockk<SXMMetadataService>(relaxed = true)
    private val espn = mockk<ESPNScoreService>(relaxed = true)
    private val customPlaylists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
    private val customPlaylistsLoaded = MutableStateFlow(true)
    private val customPlaylistLoadError = MutableStateFlow<String?>(null)
    private val customManager = mockk<CustomPlaylistManager> {
        every { playlists } returns customPlaylists
        every { isLoaded } returns customPlaylistsLoaded
        every { loadError } returns customPlaylistLoadError
    }
    private var mappingCallback: (() -> Unit)? = null
    private var storedSettings = AppSettings()

    private fun manager(selection: Set<String>?): AccountManager {
        val settings = AppSettings(sxmChannelGroups = selection)
        storedSettings = settings
        coEvery { persistence.loadSettings() } returns settings
        coEvery { persistence.loadSettingsResult() } returns SettingsLoadResult.Loaded(settings)
        every { persistence.loadSettingsSync() } returns settings
        coEvery { persistence.updateSettings(any()) } coAnswers {
            storedSettings = firstArg<(AppSettings) -> AppSettings>().invoke(storedSettings)
            storedSettings
        }
        coEvery { persistence.loadAccounts() } returns emptyList()
        coEvery { persistence.loadFavoriteIds() } returns emptyList()
        coEvery { persistence.loadLovedTracks() } returns emptyList()
        every { espn.gamesByChannel } returns MutableStateFlow(emptyMap())
        every { metadataService.onMappingBuilt = any() } answers {
            mappingCallback = firstArg()
        }
        return AccountManager(
            persistenceService = persistence,
            client = OkHttpClient(),
            sxmMetadataService = metadataService,
            espnScoreService = espn,
            customPlaylistManager = customManager,
        )
    }

    @Test
    fun `deselection clears state and rejects non-cooperative track and feed responses`() = runTest {
        val selectedGroup = "Satellite"
        val manager = manager(setOf(selectedGroup))
        manager.awaitInitialLoad()
        val channel = TestFixtures.makeChannel(id = "c1", name = "The Highway", group = selectedGroup)
        every { metadataService.stationIdForChannel("c1") } returns "station"

        coEvery { metadataService.getRecentTrack("station") } returns Result.success(
            TrackMetadata(title = "Current song", artist = "Current artist"),
        )
        coEvery { metadataService.getFeed() } returns mapOf(
            "c1" to TrackMetadata(title = "Current feed", artist = "Current artist"),
        )
        every { metadataService.hasMappedChannels() } returns true

        manager.startTrackMetadataPolling(channel)
        mappingCallback?.invoke()
        runCurrent()
        assertEquals("Current song", manager.trackMetadata.value[channel.name]?.title)
        assertEquals("Current feed", manager.feedMetadata.value["c1"]?.title)

        val trackStarted = CompletableDeferred<Unit>()
        val feedStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { metadataService.getRecentTrack("station") } coAnswers {
            trackStarted.complete(Unit)
            withContext(NonCancellable) { release.await() }
            Result.success(TrackMetadata(title = "Stale song", artist = "Stale artist"))
        }
        coEvery { metadataService.getFeed() } coAnswers {
            feedStarted.complete(Unit)
            withContext(NonCancellable) { release.await() }
            mapOf("c1" to TrackMetadata(title = "Stale feed", artist = "Stale artist"))
        }
        advanceTimeBy(30_000L)
        runCurrent()
        trackStarted.await()
        feedStarted.await()

        manager.updateSxmChannelGroups(emptySet())
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(31_000L)
        runCurrent()

        assertTrue(manager.trackMetadata.value.isEmpty())
        assertTrue(manager.feedMetadata.value.isEmpty())
        coVerify(atLeast = 2) { metadataService.getRecentTrack("station") }
        coVerify(atLeast = 2) { metadataService.getFeed() }
        verify { metadataService.matchChannels(any(), emptySet(), any()) }
    }

    @Test
    fun `selection cancellation is rethrown and is not reported as a save failure`() = runTest {
        val manager = manager(setOf("Old"))
        manager.awaitInitialLoad()
        coEvery { persistence.updateSettings(any()) } throws CancellationException("owner stopped")

        try {
            manager.updateSxmChannelGroups(setOf("New"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected structured-concurrency behavior.
        }

        assertEquals(setOf("New"), manager.sxmChannelGroups.value)
        assertEquals(null, manager.sxmSelectionSaveError.value)
    }

    @Test
    fun `save failure restores the previous explicit selection`() = runTest {
        val manager = manager(setOf("Old"))
        manager.awaitInitialLoad()
        coEvery { persistence.updateSettings(any()) } throws java.io.IOException("disk full")

        val result = manager.updateSxmChannelGroups(setOf("New"))

        assertTrue(result.isFailure)
        assertEquals(setOf("Old"), manager.sxmChannelGroups.value)
        assertNotNull(manager.sxmSelectionSaveError.value)
    }

    @Test
    fun `immediate toggle save failure is contained and restores the rendered selection`() = runTest {
        val manager = manager(setOf("Old"))
        manager.awaitInitialLoad()
        coEvery { persistence.updateSettings(any()) } throws java.io.IOException("disk full")

        manager.requestToggleSxmChannelGroup("New")
        runCurrent()

        assertEquals(setOf("Old"), manager.sxmChannelGroups.value)
        assertNotNull(manager.sxmSelectionSaveError.value)
    }

    @Test
    fun `selecting the tuned channel group attaches metadata without a retune`() = runTest {
        val manager = manager(emptySet())
        manager.awaitInitialLoad()
        val channel = TestFixtures.makeChannel(id = "c1", name = "The Highway", group = "Satellite")
        every { metadataService.stationIdForChannel("c1") } returns "station"
        coEvery { metadataService.getRecentTrack("station") } returns Result.success(null)

        manager.startTrackMetadataPolling(channel)
        manager.updateSxmChannelGroups(setOf("Satellite"))
        mappingCallback?.invoke()
        runCurrent()

        coVerify { metadataService.getRecentTrack("station") }
        manager.stopTrackMetadataPolling()
    }

    @Test
    fun `upgrade migration waits for local custom inventory to finish loading`() = runTest {
        customPlaylistsLoaded.value = false
        val manager = manager(null)
        manager.awaitInitialLoad()
        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.Loading)
        coVerify(exactly = 0) { persistence.updateSettings(any()) }

        customPlaylists.value = listOf(
            CustomPlaylist(
                name = "Local",
                groups = listOf(
                    CustomPlaylistGroup(
                        name = "SiriusXM",
                        entries = listOf(CustomPlaylistEntry(name = "The Highway", streamURL = "https://example.test/live")),
                    ),
                ),
            ),
        )
        customPlaylistsLoaded.value = true
        runCurrent()

        coVerify { persistence.updateSettings(any()) }
        assertEquals(setOf("SiriusXM"), manager.sxmChannelGroups.value)
    }

    @Test
    fun `failed local custom inventory remains partial and does not migrate`() = runTest {
        customPlaylistLoadError.value = "Custom channel groups could not be loaded."
        customPlaylists.value = listOf(
            CustomPlaylist(
                name = "Local",
                groups = listOf(CustomPlaylistGroup(name = "SiriusXM")),
            ),
        )
        val manager = manager(null)

        manager.awaitInitialLoad()
        runCurrent()

        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.PartialFailure)
        assertEquals(null, manager.sxmChannelGroups.value)
        coVerify(exactly = 0) { persistence.updateSettings(any()) }

        customPlaylistLoadError.value = null
        runCurrent()

        assertEquals(setOf("SiriusXM"), manager.sxmChannelGroups.value)
        coVerify(exactly = 1) { persistence.updateSettings(any()) }
    }

    @Test
    fun `migration runs once and later legacy-looking names do not change selection`() = runTest {
        customPlaylists.value = listOf(
            CustomPlaylist(
                name = "Local",
                groups = listOf(CustomPlaylistGroup(name = "SiriusXM")),
            ),
        )
        val manager = manager(null)
        manager.awaitInitialLoad()
        runCurrent()
        assertEquals(setOf("SiriusXM"), manager.sxmChannelGroups.value)

        customPlaylists.value = customPlaylists.value.map { playlist ->
            playlist.copy(groups = playlist.groups + CustomPlaylistGroup(name = "sxm"))
        }
        runCurrent()

        assertEquals(setOf("SiriusXM"), manager.sxmChannelGroups.value)
        coVerify(exactly = 1) { persistence.updateSettings(any()) }
    }

    @Test
    fun `provider reload rematches without changing explicit selection`() = runTest {
        val manager = manager(setOf("Satellite"))
        manager.awaitInitialLoad()

        manager.loadAllChannels()

        assertEquals(setOf("Satellite"), manager.sxmChannelGroups.value)
        verify { metadataService.matchChannels(any(), setOf("Satellite"), any()) }
    }

    @Test
    fun `inventory retry reloads custom storage and providers`() = runTest {
        every { customManager.retryLoad() } returns Unit
        val manager = manager(setOf("Satellite"))
        manager.awaitInitialLoad()

        manager.retrySxmChannelGroupInventory()

        verify { customManager.retryLoad() }
        verify(atLeast = 2) { metadataService.matchChannels(any(), setOf("Satellite"), any()) }
    }

    @Test
    fun `raw inventory counts same exact name across providers and hidden or empty custom groups`() {
        val counts = rawChannelGroupCounts(
            providerChannels = listOf(
                TestFixtures.makeChannel(id = "a:1", group = "Satellite"),
                TestFixtures.makeChannel(id = "b:2", group = "Satellite"),
                TestFixtures.makeChannel(id = "hidden", group = "Hidden Group"),
            ),
            playlists = listOf(
                CustomPlaylist(
                    name = "Local",
                    groups = listOf(
                        CustomPlaylistGroup(name = "Satellite", entries = listOf(CustomPlaylistEntry(name = "Local", streamURL = "https://example.test/live"))),
                        CustomPlaylistGroup(name = "Empty Custom"),
                    ),
                ),
            ),
        )

        assertEquals(mapOf("Satellite" to 3, "Hidden Group" to 1, "Empty Custom" to 0), counts)
    }

    @Test
    fun `custom group create rename and delete rematch against unchanged selection`() = runTest {
        val manager = manager(setOf("Custom SXM"))
        manager.awaitInitialLoad()
        val entry = CustomPlaylistEntry(id = "entry", name = "The Highway", streamURL = "https://example.test/live")
        val created = CustomPlaylist(
            id = "playlist",
            name = "Local",
            groups = listOf(CustomPlaylistGroup(id = "group", name = "Custom SXM", entries = listOf(entry))),
        )

        customPlaylists.value = listOf(created)
        runCurrent()
        verify { metadataService.matchChannels(match { it.size == 1 && it.single().group == "Custom SXM" }, setOf("Custom SXM"), any()) }

        customPlaylists.value = listOf(created.copy(groups = listOf(created.groups.single().copy(name = "Renamed"))))
        runCurrent()
        verify { metadataService.matchChannels(match { it.size == 1 && it.single().group == "Renamed" }, setOf("Custom SXM"), any()) }

        customPlaylists.value = listOf(created.copy(groups = emptyList()))
        runCurrent()
        verify { metadataService.matchChannels(match { it.isEmpty() }, setOf("Custom SXM"), any()) }
        assertEquals(setOf("Custom SXM"), manager.sxmChannelGroups.value)
    }
}
