package com.adagiostream.android.ui.screens.podcasts

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeOrder
import com.adagiostream.android.service.audiobookshelf.PodcastPlaybackContext
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.ui.screens.audiobooks.AbsLoadState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * PodcastEpisodeListViewModel tests (beads_adagio-59p.2.1): episode sort per
 * the persisted order setting, and on-visibility progress hydration
 * (fetch-once caching, 404 → hydrated-unplayed).
 */
class PodcastEpisodeListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private var storedSettings = AppSettings()

    private val fakeAccountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val fakeAccountRepository = object : AccountRepository {
        override val accounts: StateFlow<List<Account>> = fakeAccountsFlow
    }

    /** Counts progress-endpoint hits per episode id. */
    private val progressHits = ConcurrentHashMap<String, AtomicInteger>()

    /** Records playbackLauncher invocations (beads_adagio-59p.2.2). */
    data class LaunchedPlay(
        val accountId: String,
        val showId: String,
        val episodeId: String,
        val context: PodcastPlaybackContext?,
    )
    private val launchedPlays = mutableListOf<LaunchedPlay>()

    // show1's episodes arrive in scrambled wire order; ep-mid is undated.
    private val itemBody = """{"id":"show1","media":{"metadata":{"title":"The Show"},"episodes":[
        {"id":"ep-old","title":"Old","pubDate":"Thu, 01 Jan 2026 00:00:00 GMT"},
        {"id":"ep-undated","title":"Undated"},
        {"id":"ep-new","title":"New","pubDate":"Sun, 01 Mar 2026 00:00:00 GMT"},
        {"id":"ep-mid","title":"Mid","pubDate":"Sun, 01 Feb 2026 00:00:00 GMT"}]}}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/items/show1" -> mockJson(200, itemBody)
                    path.startsWith("/api/me/progress/show1/") -> {
                        val episodeId = path.substringAfterLast("/")
                        progressHits.getOrPut(episodeId) { AtomicInteger() }.incrementAndGet()
                        when (episodeId) {
                            "ep-new" -> mockJson(200, """{"progress":0.42,"isFinished":false}""")
                            else -> mockJson(404, """{"error":"not found"}""")
                        }
                    }
                    else -> mockJson(404, """{"error":"not found"}""")
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mockJson(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    private fun buildViewModel(): PodcastEpisodeListViewModel {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val factory = AudiobookshelfApiFactory { host, username, password, tokens, onTokensChanged ->
            AudiobookshelfApi(
                client = client,
                host = host,
                auth = AudiobookshelfAuth(
                    client = client,
                    host = host,
                    username = username,
                    password = password,
                    initialTokens = tokens,
                    onTokensChanged = onTokensChanged,
                ),
            )
        }

        val accountManager = mockk<AccountManager>(relaxed = true) {
            every { accounts } returns fakeAccountsFlow
        }
        val persistenceService = mockk<PersistenceService> {
            coEvery { loadSettings() } answers { storedSettings }
        }

        return PodcastEpisodeListViewModel(
            accountRepository = fakeAccountRepository,
            apiProvider = AudiobookshelfApiProvider(
                accountManager = accountManager,
                factory = factory,
            ),
            persistenceService = persistenceService,
            playbackLauncher = { account, showId, episodeId, context ->
                launchedPlays += LaunchedPlay(account.id, showId, episodeId, context)
            },
            downloadActions = mockk(relaxed = true) {
                every { observeAll() } returns kotlinx.coroutines.flow.flowOf(emptyList())
            },
            savedStateHandle = SavedStateHandle(mapOf("itemId" to "show1")),
        )
    }

    private fun setAbsAccount() {
        fakeAccountsFlow.value = listOf(
            Account(
                id = "test-abs",
                name = "My Audiobookshelf",
                type = AccountType.Audiobookshelf(
                    host = server.url("/").toString().trimEnd('/'),
                    username = "alice",
                    accessToken = "acc-1",
                    refreshToken = "ref-1",
                ),
            ),
        )
    }

    @Test
    fun `load sorts newest-first by default with undated last`() = runTest {
        val viewModel = buildViewModel()
        setAbsAccount()

        viewModel.episodesState.test {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf("ep-new", "ep-mid", "ep-old", "ep-undated"),
            viewModel.episodes.value.map { it.id },
        )
        assertEquals("The Show", viewModel.showTitle.value)
    }

    @Test
    fun `load respects the persisted oldest-first setting`() = runTest {
        storedSettings = AppSettings(podcastEpisodeSortOrder = PodcastEpisodeOrder.OLDEST_FIRST)
        val viewModel = buildViewModel()
        setAbsAccount()

        viewModel.episodesState.test {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf("ep-old", "ep-mid", "ep-new", "ep-undated"),
            viewModel.episodes.value.map { it.id },
        )
    }

    @Test
    fun `visible rows hydrate once - repeat visibility does not refetch`() = runTest {
        val viewModel = buildViewModel()
        setAbsAccount()

        viewModel.episodeProgress.test {
            assertEquals(emptyMap<String, Any?>(), awaitItem())

            viewModel.onEpisodeVisible("ep-new")
            val hydrated = awaitItem()
            assertEquals(0.42, hydrated["show1/ep-new"]?.progress)

            // Scroll-back / recomposition: same episode becomes visible again.
            viewModel.onEpisodeVisible("ep-new")
            viewModel.onEpisodeVisible("ep-new")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, progressHits["ep-new"]?.get())
    }

    @Test
    fun `playEpisode launches with the loaded context - list, order and show title`() = runTest {
        storedSettings = AppSettings(podcastEpisodeSortOrder = PodcastEpisodeOrder.OLDEST_FIRST)
        val viewModel = buildViewModel()
        setAbsAccount()

        viewModel.episodesState.test {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.playEpisode("ep-mid")

        assertEquals(1, launchedPlays.size)
        val play = launchedPlays.single()
        assertEquals("test-abs", play.accountId)
        assertEquals("show1", play.showId)
        assertEquals("ep-mid", play.episodeId)
        val context = play.context!!
        assertEquals("show1", context.libraryItemId)
        assertEquals("The Show", context.showTitle)
        assertEquals(PodcastEpisodeOrder.OLDEST_FIRST, context.order)
        assertEquals(
            listOf("ep-old", "ep-mid", "ep-new", "ep-undated"),
            context.episodes.map { it.id },
        )
    }

    @Test
    fun `playEpisode without an ABS account is a no-op`() = runTest {
        val viewModel = buildViewModel()
        viewModel.playEpisode("ep-old")
        assertTrue(launchedPlays.isEmpty())
    }

    @Test
    fun `a 404 progress response hydrates as unplayed`() = runTest {
        val viewModel = buildViewModel()
        setAbsAccount()

        viewModel.episodeProgress.test {
            assertEquals(emptyMap<String, Any?>(), awaitItem())
            viewModel.onEpisodeVisible("ep-old")
            val hydrated = awaitItem()
            assertTrue("show1/ep-old" in hydrated)
            assertNull(hydrated["show1/ep-old"])
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, progressHits["ep-old"]?.get())
    }
}
