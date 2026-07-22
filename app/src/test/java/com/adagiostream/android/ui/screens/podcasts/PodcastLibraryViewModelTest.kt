package com.adagiostream.android.ui.screens.podcasts

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeEntry
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * PodcastLibraryViewModel tests (beads_adagio-59p.2.1): show-grid loading,
 * the Continue Listening episode shelf (partition of the mixed
 * items-in-progress response), and Recent Episodes aggregation (per-show
 * sort, grouped by show, detail fetches cached).
 *
 * The server dispatcher is path-keyed because shows and shelf requests run
 * concurrently.
 */
class PodcastLibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: PodcastLibraryViewModel

    private val fakeAccountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val fakeAccountRepository = object : AccountRepository {
        override val accounts: StateFlow<List<Account>> = fakeAccountsFlow
    }

    /** Counts expanded-item fetches per show id (Recent Episodes caching). */
    private val itemHits = ConcurrentHashMap<String, AtomicInteger>()

    /** Records playbackLauncher invocations (beads_adagio-59p.2.2). */
    data class LaunchedPlay(
        val accountId: String,
        val showId: String,
        val episodeId: String,
        val context: PodcastPlaybackContext?,
    )
    private val launchedPlays = mutableListOf<LaunchedPlay>()

    private val itemsBody = """{"results":[
        {"id":"showA","media":{"metadata":{"title":"Alpha Cast","author":"Ann"}}},
        {"id":"showB","media":{"metadata":{"title":"Beta Cast"}}}]}"""

    // Shelf: one book, one podcast episode.
    private val inProgressBody = """{"libraryItems":[
        {"id":"bk1","media":{"metadata":{"title":"Borne"}},
         "userMediaProgress":{"progress":0.4,"isFinished":false}},
        {"id":"showA","media":{"metadata":{"title":"Alpha Cast"}},
         "recentEpisode":{"id":"epA1","title":"A1"}}]}"""

    private val showABody = """{"id":"showA","media":{"metadata":{"title":"Alpha Cast"},"episodes":[
        {"id":"epA-old","title":"A old","pubDate":"Thu, 01 Jan 2026 00:00:00 GMT"},
        {"id":"epA-new","title":"A new","pubDate":"Sun, 01 Feb 2026 00:00:00 GMT"}]}}"""

    private val showBBody = """{"id":"showB","media":{"metadata":{"title":"Beta Cast"},"episodes":[
        {"id":"epB-1","title":"B 1","pubDate":"Fri, 02 Jan 2026 00:00:00 GMT"}]}}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/libraries/lib1/items" -> mockJson(200, itemsBody)
                    path == "/api/me/items-in-progress" -> mockJson(200, inProgressBody)
                    path == "/api/items/showA" -> {
                        itemHits.getOrPut("showA") { AtomicInteger() }.incrementAndGet()
                        mockJson(200, showABody)
                    }
                    path == "/api/items/showB" -> {
                        itemHits.getOrPut("showB") { AtomicInteger() }.incrementAndGet()
                        mockJson(200, showBBody)
                    }
                    else -> mockJson(404, """{"error":"not found"}""")
                }
            }
        }

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
            coEvery { loadSettings() } returns AppSettings()
        }

        viewModel = PodcastLibraryViewModel(
            accountRepository = fakeAccountRepository,
            apiProvider = AudiobookshelfApiProvider(
                accountManager = accountManager,
                factory = factory,
            ),
            persistenceService = persistenceService,
            playbackLauncher = { account, showId, episodeId, context ->
                launchedPlays += LaunchedPlay(account.id, showId, episodeId, context)
            },
            savedStateHandle = SavedStateHandle(mapOf("libraryId" to "lib1")),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mockJson(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

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
    fun `load maps library items to shows`() = runTest {
        setAbsAccount()

        viewModel.showsState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("showA", "showB"), viewModel.shows.value.map { it.id })
        assertEquals("Alpha Cast", viewModel.shows.value.first().title)
        assertEquals("Ann", viewModel.shows.value.first().author)
        assertEquals("lib1", viewModel.shows.value.first().libraryId)
    }

    @Test
    fun `continue listening shelf keeps only podcast episodes`() = runTest {
        setAbsAccount()

        viewModel.continueListening.test(timeout = 10.seconds) {
            assertEquals(emptyList<Any>(), awaitItem())
            viewModel.load()
            val entries = awaitItem()
            // bk1 (a book) is filtered out of the episode shelf.
            assertEquals(listOf("epA1"), entries.map { it.episode.id })
            assertEquals("showA", entries.single().showLibraryItemId)
            assertEquals("Alpha Cast", entries.single().showTitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shelf failure never blocks the show grid`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/api/libraries/lib1/items" -> mockJson(200, itemsBody)
                    else -> mockJson(500, """{"error":"boom"}""")
                }
        }
        setAbsAccount()

        viewModel.showsState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<Any>(), viewModel.continueListening.value)
    }

    @Test
    fun `recent episodes are per-show sorted and grouped by show, not interleaved`() = runTest {
        setAbsAccount()
        viewModel.showsState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.recentState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.setBrowseMode(PodcastLibraryViewModel.BrowseMode.RECENT)
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Grouped by show (A then B); within A newest-first. A true cross-show
        // interleave would put epB-1 (Jan 2) between A's Feb and Jan episodes.
        assertEquals(
            listOf("epA-new", "epA-old", "epB-1"),
            viewModel.recentEpisodes.value.map { it.episode.id },
        )
        assertEquals(
            listOf("showA", "showA", "showB"),
            viewModel.recentEpisodes.value.map { it.showLibraryItemId },
        )
    }

    @Test
    fun `re-entering recent episodes reuses cached show details`() = runTest {
        setAbsAccount()
        viewModel.showsState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.recentState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.setBrowseMode(PodcastLibraryViewModel.BrowseMode.RECENT)
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())

            // Explicit reload (retry path) — details come from the cache.
            viewModel.retryRecent()
            assertEquals(AbsLoadState.Idle, awaitItem())
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, itemHits["showA"]?.get())
        assertEquals(1, itemHits["showB"]?.get())
    }

    // ---- playEpisode (beads_adagio-59p.2.2) --------------------------------

    private fun entry(showId: String, episodeId: String, showTitle: String? = null) =
        PodcastEpisodeEntry(
            episode = AbsEpisode(id = episodeId, title = episodeId),
            showLibraryItemId = showId,
            showTitle = showTitle,
        )

    @Test
    fun `shelf tap without cached episodes launches with a null context`() = runTest {
        setAbsAccount()
        viewModel.showsState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.playEpisode(entry("showA", "epA1", "Alpha Cast"))

        val play = launchedPlays.single()
        assertEquals("showA", play.showId)
        assertEquals("epA1", play.episodeId)
        // No episode list in hand — the coordinator fetches the show itself.
        assertEquals(null, play.context)
    }

    @Test
    fun `recent tap with cached episodes launches with the full context`() = runTest {
        setAbsAccount()
        viewModel.showsState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.recentState.test(timeout = 10.seconds) {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.setBrowseMode(PodcastLibraryViewModel.BrowseMode.RECENT)
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.playEpisode(entry("showA", "epA-old", "Alpha Cast"))

        val play = launchedPlays.single()
        val context = play.context!!
        assertEquals("showA", context.libraryItemId)
        assertEquals("Alpha Cast", context.showTitle)
        assertEquals(PodcastEpisodeOrder.NEWEST_FIRST, context.order)
        // Wire-order episode list from the cached show detail.
        assertEquals(listOf("epA-old", "epA-new"), context.episodes.map { it.id })
    }

    @Test
    fun `playEpisode without an ABS account is a no-op`() = runTest {
        viewModel.playEpisode(entry("showA", "epA1"))
        assertEquals(emptyList<LaunchedPlay>(), launchedPlays)
    }
}
