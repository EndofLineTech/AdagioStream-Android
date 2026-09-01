package com.adagiostream.android.service.account

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.SXMMetadataService
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.persistence.SettingsLoadResult
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountManagerProviderInventoryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private val persistence = mockk<PersistenceService>(relaxed = true)
    private val metadata = mockk<SXMMetadataService>(relaxed = true)
    private val espn = mockk<ESPNScoreService>(relaxed = true)
    private val custom = mockk<CustomPlaylistManager> {
        every { playlists } returns MutableStateFlow(emptyList())
        every { isLoaded } returns MutableStateFlow(true)
        every { loadError } returns MutableStateFlow(null)
    }
    private var storedSettings = AppSettings(sxmChannelGroups = null)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { espn.gamesByChannel } returns MutableStateFlow(emptyMap())
        coEvery { persistence.loadFavoriteIds() } returns emptyList()
        coEvery { persistence.loadLovedTracks() } returns emptyList()
        coEvery { persistence.loadSettingsResult() } answers {
            SettingsLoadResult.MigrationUninitialized(storedSettings)
        }
        coEvery { persistence.updateSettings(any()) } coAnswers {
            storedSettings = firstArg<(AppSettings) -> AppSettings>().invoke(storedSettings)
            storedSettings
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun m3u(group: String, name: String = group): String = """
        #EXTM3U
        #EXTINF:-1 group-title="$group",$name
        https://stream.example/live
    """.trimIndent()

    private fun account(id: String, path: String, enabled: Boolean = true, epgPath: String? = null) = Account(
        id = id,
        name = id,
        type = AccountType.M3U(
            url = server.url(path).toString(),
            epgUrl = epgPath?.let { server.url(it).toString() },
        ),
        isEnabled = enabled,
    )

    private fun epg(title: String) = """
        <tv>
          <programme channel="channel" start="20250101120000 +0000" stop="20250101130000 +0000">
            <title>$title</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun manager(accounts: List<Account>): AccountManager {
        coEvery { persistence.loadAccounts() } returns accounts
        return AccountManager(
            persistenceService = persistence,
            client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
            sxmMetadataService = metadata,
            espnScoreService = espn,
            customPlaylistManager = custom,
        )
    }

    @Test
    fun `complete production provider load includes disabled accounts without exposing their channels`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/enabled.m3u" -> response(m3u("SiriusXM Enabled"))
                "/disabled.m3u" -> response(m3u("SXM Disabled"))
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val manager = manager(
            listOf(
                account("enabled", "/enabled.m3u"),
                account("disabled", "/disabled.m3u", enabled = false),
            ),
        )

        manager.awaitInitialLoad()
        advanceUntilIdle()

        val inventory = manager.rawChannelGroupInventory.value as RawChannelGroupInventory.Complete
        assertEquals(mapOf("SiriusXM Enabled" to 1, "SXM Disabled" to 1), inventory.groupCounts)
        assertEquals(listOf("SiriusXM Enabled"), manager.channels.value.map { it.group })
        assertEquals(setOf("SiriusXM Enabled", "SXM Disabled"), manager.sxmChannelGroups.value)

        manager.toggleGroupEnabled("SiriusXM Enabled")
        assertFalse(manager.groups.value.any { it.name == "SiriusXM Enabled" })
        assertTrue((manager.rawChannelGroupInventory.value as RawChannelGroupInventory.Complete).groupCounts.containsKey("SiriusXM Enabled"))
        assertTrue("SiriusXM Enabled" in manager.sxmChannelGroups.value.orEmpty())
        verify { metadata.matchChannels(match { it.any { channel -> channel.group == "SiriusXM Enabled" } }, match { "SiriusXM Enabled" in it }, any()) }
    }

    @Test
    fun `partial provider failure waits and retry migrates from complete inventory`() = runTest {
        val failSecond = AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/one.m3u" -> response(m3u("SiriusXM One"))
                "/two.m3u" -> if (failSecond.get()) MockResponse.Builder().code(500).build() else response(m3u("SXM Two"))
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val manager = manager(listOf(account("one", "/one.m3u"), account("two", "/two.m3u")))

        manager.awaitInitialLoad()
        runCurrent()
        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.PartialFailure)
        assertNull(manager.sxmChannelGroups.value)

        failSecond.set(false)
        manager.loadAllChannels()
        runCurrent()

        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.Complete)
        assertEquals(setOf("SiriusXM One", "SXM Two"), manager.sxmChannelGroups.value)
    }

    @Test
    fun `stale successful reload cannot replace newer failed inventory or migrate`() = runTest {
        val requestNumber = AtomicInteger()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (requestNumber.incrementAndGet()) {
                1 -> MockResponse.Builder().code(500).build()
                2 -> {
                    oldStarted.countDown()
                    releaseOld.await(5, TimeUnit.SECONDS)
                    response(m3u("SiriusXM Stale"))
                }
                else -> MockResponse.Builder().code(500).build()
            }
        }
        val manager = manager(listOf(account("provider", "/provider.m3u")))
        manager.awaitInitialLoad()
        runCurrent()
        assertNull(manager.sxmChannelGroups.value)

        val old = async { manager.loadAllChannels() }
        runCurrent()
        assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
        val newer = async { manager.loadAllChannels() }
        newer.await()
        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.PartialFailure)

        releaseOld.countDown()
        old.await()
        runCurrent()

        assertTrue(manager.rawChannelGroupInventory.value is RawChannelGroupInventory.PartialFailure)
        assertNull(manager.sxmChannelGroups.value)
    }

    @Test
    fun `stale EPG response cannot replace newer EPG or run downstream publications`() = runTest {
        storedSettings = AppSettings(sxmChannelGroups = emptySet())
        val epgRequest = AtomicInteger()
        val oldEpgStarted = CountDownLatch(1)
        val releaseOldEpg = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/provider.m3u" -> response(m3u("Channels"))
                "/guide.xml" -> when (epgRequest.incrementAndGet()) {
                    1 -> response(epg("Initial"))
                    2 -> {
                        oldEpgStarted.countDown()
                        releaseOldEpg.await(5, TimeUnit.SECONDS)
                        response(epg("Stale"))
                    }
                    else -> response(epg("Newer"))
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val manager = manager(listOf(account("provider", "/provider.m3u", epgPath = "/guide.xml")))
        manager.awaitInitialLoad()
        assertEquals("Initial", manager.epgEntries.value.getValue("channel").single().title)

        val old = async { manager.loadAllChannels() }
        var oldStarted = false
        repeat(500) {
            if (!oldStarted) {
                runCurrent()
                oldStarted = oldEpgStarted.await(10, TimeUnit.MILLISECONDS)
            }
        }
        assertTrue(oldStarted)
        val newer = async { manager.loadAllChannels() }
        newer.await()
        assertEquals("Newer", manager.epgEntries.value.getValue("channel").single().title)
        verify { espn.matchChannels(any(), any()) }

        clearMocks(espn, metadata, answers = false, recordedCalls = true)
        releaseOldEpg.countDown()
        old.await()
        runCurrent()

        assertEquals("Newer", manager.epgEntries.value.getValue("channel").single().title)
        verify(exactly = 0) { espn.matchChannels(any(), any()) }
        verify(exactly = 0) { metadata.matchChannels(any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { persistence.updateSettings(any()) }
    }

    private fun response(body: String) = MockResponse.Builder().code(200).body(body).build()
}
