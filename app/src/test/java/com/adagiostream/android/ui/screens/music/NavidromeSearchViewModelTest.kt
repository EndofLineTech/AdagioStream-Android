package com.adagiostream.android.ui.screens.music

import app.cash.turbine.test
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.player.LibraryTrackPlayer
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import com.adagiostream.android.service.player.MusicQueueManager
import com.adagiostream.android.service.player.PlaybackSource
import com.adagiostream.android.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NavidromeSearchViewModel state + debounce tests (baw.4.1).
 *
 * Tests:
 *  - blank query short-circuits (no network call, stays Idle)
 *  - 300ms debounce — rapid typing fires only ONE request after settle
 *  - state machine: Idle → Loading → Loaded/Empty/Error
 *  - playSearchTrack enqueues all search songs as queue from tapped index
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavidromeSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: NavidromeApi
    private lateinit var viewModel: NavidromeSearchViewModel

    private val playedSources = mutableListOf<PlaybackSource.Library>()
    private val fakeTrackPlayer = object : LibraryTrackPlayer {
        override fun playLibraryTrack(streamUrl: String, source: PlaybackSource.Library, startPositionMs: Long) {
            playedSources += source
        }
        override fun stop() {}
    }

    private val fakeAccountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val fakeAccountRepository = object : AccountRepository {
        override val accounts: StateFlow<List<Account>> = fakeAccountsFlow
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        api = NavidromeApi(
            client = client,
            host = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "sesame",
        )

        viewModel = NavidromeSearchViewModel(
            accountRepository = fakeAccountRepository,
            navidromeApiFactory = NavidromeApiFactory { host, user, pass ->
                NavidromeApi(client = client, host = host, username = user, password = pass)
            },
            musicPlaybackCoordinator = MusicPlaybackCoordinator(
                MusicQueueManager(),
                fakeTrackPlayer,
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun setSubsonicAccount() {
        fakeAccountsFlow.value = listOf(
            Account(
                id = "test-subsonic",
                name = "My Navidrome",
                type = AccountType.Subsonic(
                    host = server.url("/").toString().trimEnd('/'),
                    username = "alice",
                    password = "sesame",
                ),
            ),
        )
    }

    private fun mockOk(body: String): MockResponse =
        MockResponse.Builder().code(200).body(body).build()

    // -------------------------------------------------------------------------
    // Blank-query short-circuit
    // -------------------------------------------------------------------------

    @Test
    fun `initial searchState is Idle`() = runTest {
        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blank query does not trigger network call and stays Idle`() = runTest {
        setSubsonicAccount()
        // No server response queued — if a network call is made, the test would hang/fail

        viewModel.onQueryChanged("")

        // With UnconfinedTestDispatcher, the debounce fires right away for blank — still Idle
        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `whitespace-only query does not trigger network call`() = runTest {
        setSubsonicAccount()

        viewModel.onQueryChanged("   ")

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `clearSearch resets state to Idle`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(SEARCH_OK_FIXTURE))

        // Do a search first
        viewModel.onQueryChanged("rock")
        viewModel.searchState.test {
            val states = mutableListOf<NavidromeSearchViewModel.SearchState>()
            repeat(5) {
                states.add(awaitItem())
                if (states.last() is NavidromeSearchViewModel.SearchState.Loaded) return@test
            }
            cancelAndIgnoreRemainingEvents()
        }

        // Then clear
        viewModel.clearSearch()

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // Debounce — only ONE request fires for rapid sequential query changes
    // -------------------------------------------------------------------------

    @Test
    fun `debounce - rapid typing only fires ONE network request after 300ms settle`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(SEARCH_OK_FIXTURE))

        viewModel.searchState.test {
            awaitItem() // initial Idle

            // No request should have been made yet
            assertEquals("No requests before typing", 0, server.requestCount)

            // Rapid typing — "r" → "ro" → "rock" with no virtual time between them
            viewModel.onQueryChanged("r")
            viewModel.onQueryChanged("ro")
            viewModel.onQueryChanged("rock")

            // State is still Idle — debounce delay has not fired yet
            assertEquals(
                "Still Idle immediately after typing",
                NavidromeSearchViewModel.SearchState.Idle,
                viewModel.searchState.value,
            )

            // Await Loaded — runTest auto-advances virtual clock so the 300ms
            // delay fires, then the IO completes on the real dispatcher.
            // We skip Loading here because we may or may not observe it depending
            // on how fast the IO thread responds.
            var finalState: NavidromeSearchViewModel.SearchState = NavidromeSearchViewModel.SearchState.Idle
            repeat(5) {
                val s = awaitItem()
                finalState = s
                if (s is NavidromeSearchViewModel.SearchState.Loaded) return@test
            }
            assertTrue("Expected Loaded, got $finalState", finalState is NavidromeSearchViewModel.SearchState.Loaded)

            cancelAndIgnoreRemainingEvents()
        }

        // Only ONE request fired despite three rapid keystrokes
        assertEquals(
            "Debounce should fire exactly 1 request for 3 rapid emissions",
            1,
            server.requestCount,
        )
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    @Test
    fun `non-blank query transitions to Loading then Loaded`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(SEARCH_OK_FIXTURE))

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())

            viewModel.onQueryChanged("radiohead")

            assertEquals(NavidromeSearchViewModel.SearchState.Loading, awaitItem())

            val loaded = awaitItem()
            assertTrue("Expected Loaded state", loaded is NavidromeSearchViewModel.SearchState.Loaded)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search populates artists albums and songs`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(SEARCH_OK_FIXTURE))

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            viewModel.onQueryChanged("radiohead")
            awaitItem() // Loading
            val loaded = awaitItem()
            assertTrue(loaded is NavidromeSearchViewModel.SearchState.Loaded)
            cancelAndIgnoreRemainingEvents()
        }

        val results = viewModel.searchResults.value
        assertTrue(results != null)
        assertEquals(1, results!!.artists.size)
        assertEquals("Radiohead", results.artists.first().name)
        assertEquals(1, results.albums.size)
        assertEquals(2, results.tracks.size)
    }

    @Test
    fun `search transitions to Empty when server returns no results`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(SEARCH_EMPTY_FIXTURE))

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            viewModel.onQueryChanged("zzz")
            awaitItem() // Loading
            assertEquals(NavidromeSearchViewModel.SearchState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search transitions to Error on auth failure`() = runTest {
        setSubsonicAccount()
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong credentials"}}}"""),
        )

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            viewModel.onQueryChanged("test")
            awaitItem() // Loading
            val result = awaitItem()
            assertTrue("Expected Error state", result is NavidromeSearchViewModel.SearchState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search is no-op when no Subsonic account configured`() = runTest {
        // No account set

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            viewModel.onQueryChanged("rock")
            // No state change expected — no API available
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // playSearchTrack (baw.4.1 — plays search songs as queue)
    // -------------------------------------------------------------------------

    @Test
    fun `playSearchTrack enqueues all search songs and starts from tapped index`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(SEARCH_OK_FIXTURE))

        viewModel.searchState.test {
            assertEquals(NavidromeSearchViewModel.SearchState.Idle, awaitItem())
            viewModel.onQueryChanged("radiohead")
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        val results = viewModel.searchResults.value!!
        val tracks = results.tracks
        assertEquals(2, tracks.size)

        // Tap the 2nd track
        viewModel.playSearchTrack(tracks[1])

        assertEquals(1, playedSources.size)
        val source = playedSources.first()
        assertEquals(2, source.queue.size)         // all search song results
        assertEquals(1, source.index)              // started from tapped index
        assertEquals(tracks[1].id, source.currentTrack.id)
    }

    @Test
    fun `playSearchTrack is no-op when no account configured`() = runTest {
        viewModel.playSearchTrack(
            com.adagiostream.android.testutil.TestFixtures.makeTrack(),
        )
        assertTrue(playedSources.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    companion object {
        private const val SEARCH_OK_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {
                  "artist": [
                    {"id":"ar1","name":"Radiohead","albumCount":9}
                  ],
                  "album": [
                    {"id":"al1","artistId":"ar1","artist":"Radiohead","name":"OK Computer","year":1997}
                  ],
                  "song": [
                    {"id":"s1","albumId":"al1","artistId":"ar1","title":"Airbag","artist":"Radiohead","track":1,"duration":229},
                    {"id":"s2","albumId":"al1","artistId":"ar1","title":"Paranoid Android","artist":"Radiohead","track":2,"duration":387}
                  ]
                }
              }
            }
        """

        private const val SEARCH_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {}
              }
            }
        """
    }
}
