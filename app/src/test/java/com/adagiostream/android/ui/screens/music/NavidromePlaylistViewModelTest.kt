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
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
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
 * NavidromePlaylistViewModel tests (baw.4.2, baw.4.3).
 *
 * Tests:
 *  - loadPlaylists: state machine + sorting
 *  - loadPlaylistDetail: state machine + track population
 *  - playPlaylistTrack: enqueues playlist as queue
 *  - createPlaylist: refreshes list on success
 *  - renamePlaylist: optimistic update + rollback on error
 *  - deletePlaylist: optimistic removal + rollback on error
 *  - addTrackToPlaylist: fires API call (fire-and-forget)
 */
class NavidromePlaylistViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: NavidromeApi
    private lateinit var viewModel: NavidromePlaylistViewModel

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

        viewModel = NavidromePlaylistViewModel(
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

    private fun statusOk() = mockOk("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `initial playlistsState is Idle`() = runTest {
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // loadPlaylists state machine
    // -------------------------------------------------------------------------

    @Test
    fun `loadPlaylists transitions Idle then Loading then Loaded`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))

        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())

            viewModel.loadPlaylists()

            assertEquals(NavidromePlaylistViewModel.LoadState.Loading, awaitItem())
            assertEquals(NavidromePlaylistViewModel.LoadState.Loaded, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadPlaylists populates and sorts playlists alphabetically`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))

        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            assertEquals(NavidromePlaylistViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val playlists = viewModel.playlists.value
        assertEquals(2, playlists.size)
        // Alphabetical: "My Mix" before "Workout Jams"
        assertEquals("My Mix", playlists[0].name)
        assertEquals("Workout Jams", playlists[1].name)
    }

    @Test
    fun `loadPlaylists transitions to Empty when no playlists`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_EMPTY_FIXTURE))

        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            assertEquals(NavidromePlaylistViewModel.LoadState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadPlaylists transitions to Error on auth failure`() = runTest {
        setSubsonicAccount()
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong credentials"}}}"""),
        )

        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            val result = awaitItem()
            assertTrue("Expected Error state", result is NavidromePlaylistViewModel.LoadState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadPlaylists is no-op when no Subsonic account configured`() = runTest {
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadPlaylists is no-op while already Loading`() = runTest {
        setSubsonicAccount()
        // Only ONE response queued — proves the second loadPlaylists() was a no-op.
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))

        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            assertEquals(NavidromePlaylistViewModel.LoadState.Loading, awaitItem())

            // Second call while Loading — should be a no-op (guard in loadPlaylists).
            viewModel.loadPlaylists()

            // Let the first request finish so the server is not closed while
            // the coroutine is still on IO (which would cause an uncaught exception
            // that poisons the next test via UncaughtExceptionsBeforeTest).
            assertEquals(NavidromePlaylistViewModel.LoadState.Loaded, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        // Exactly one HTTP request proves the second loadPlaylists() call was a no-op.
        assertEquals(1, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // loadPlaylistDetail state machine
    // -------------------------------------------------------------------------

    @Test
    fun `loadPlaylistDetail transitions to Loaded and populates tracks`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLIST_DETAIL_FIXTURE))

        val fakePlaylist = com.adagiostream.android.service.navidrome.NavidromePlaylist(
            id = "p1", name = "My Mix", songCount = 2,
        )

        viewModel.playlistDetailState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylistDetail(fakePlaylist)
            awaitItem() // Loading
            assertEquals(NavidromePlaylistViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, viewModel.playlistTracks.value.size)
    }

    @Test
    fun `loadPlaylistDetail exposes tracks with artist NAME not artistId`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLIST_DETAIL_FIXTURE))

        val fakePlaylist = com.adagiostream.android.service.navidrome.NavidromePlaylist(
            id = "p1", name = "My Mix", songCount = 2,
        )

        viewModel.playlistDetailState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylistDetail(fakePlaylist)
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        val tracks = viewModel.playlistTracks.value
        assertTrue(
            "Track artist must be display name, not artistId",
            tracks.all { it.artist == "Pink Floyd" },
        )
    }

    // -------------------------------------------------------------------------
    // playPlaylistTrack
    // -------------------------------------------------------------------------

    @Test
    fun `playPlaylistTrack enqueues all playlist tracks and starts from tapped index`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLIST_DETAIL_FIXTURE))

        val fakePlaylist = com.adagiostream.android.service.navidrome.NavidromePlaylist(
            id = "p1", name = "My Mix", songCount = 2,
        )

        viewModel.playlistDetailState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylistDetail(fakePlaylist)
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        val tracks = viewModel.playlistTracks.value
        assertEquals(2, tracks.size)

        // Tap second track
        viewModel.playPlaylistTrack(tracks[1])

        assertEquals(1, playedSources.size)
        val source = playedSources.first()
        assertEquals(2, source.queue.size)          // whole playlist queued
        assertEquals(1, source.index)               // started from tapped index
        assertEquals(tracks[1].id, source.currentTrack.id)
    }

    @Test
    fun `playPlaylistTrack is no-op when no account configured`() = runTest {
        viewModel.playPlaylistTrack(
            com.adagiostream.android.testutil.TestFixtures.makeTrack(),
        )
        assertTrue(playedSources.isEmpty())
    }

    // -------------------------------------------------------------------------
    // createPlaylist (baw.4.3)
    // -------------------------------------------------------------------------

    @Test
    fun `createPlaylist loads refreshed playlist list on success`() = runTest {
        setSubsonicAccount()
        // First load playlists with 2 items
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, viewModel.playlists.value.size)

        // Enqueue both responses before starting the observation so the
        // background refresh completes before the server is closed in @After.
        server.enqueue(statusOk())  // createPlaylist response
        server.enqueue(mockOk(PLAYLISTS_AFTER_CREATE_FIXTURE)) // getPlaylists refresh

        // Start collecting BEFORE calling createPlaylist so we observe the full
        // Loading → Loaded transition that comes from the internal refresh.
        viewModel.playlistsState.test {
            awaitItem() // consume current Loaded state from the first load

            viewModel.createPlaylist("Brand New Mix")

            // createPlaylist launches a coroutine that calls the API then
            // calls loadPlaylistsInternal, which sets Loading → Loaded.
            awaitItem() // Loading (refresh started)
            awaitItem() // Loaded  (refresh completed with 3 playlists)

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(3, viewModel.playlists.value.size)
        assertTrue(viewModel.playlists.value.any { it.name == "Brand New Mix" })
    }

    // -------------------------------------------------------------------------
    // renamePlaylist — optimistic update + rollback on error (baw.4.3)
    // -------------------------------------------------------------------------

    @Test
    fun `renamePlaylist updates list immediately (optimistic)`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        val original = viewModel.playlists.value.first()
        server.enqueue(statusOk()) // updatePlaylist response

        // Snapshot long-lived children (the init accounts collector never
        // completes) so we can drain ONLY the coroutine renamePlaylist spawns.
        val preexisting = viewModel.viewModelScope.coroutineContext.job.children.toSet()

        viewModel.renamePlaylist(original, "Renamed Mix")

        // Optimistic update is immediate
        val renamed = viewModel.playlists.value.firstOrNull { it.id == original.id }
        assertEquals("Renamed Mix", renamed?.name)

        // Drain the fire-and-forget updatePlaylist coroutine before the test
        // ends — otherwise it's still on Dispatchers.IO when Main is reset and
        // its resumption poisons a later test (UncaughtExceptionsBeforeTest).
        (viewModel.viewModelScope.coroutineContext.job.children.toSet() - preexisting)
            .forEach { it.join() }
    }

    @Test
    fun `renamePlaylist reverts on API error`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        val original = viewModel.playlists.value.first()
        val originalName = original.name
        // Server returns auth error
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Forbidden"}}}"""),
        )

        viewModel.renamePlaylist(original, "Should Revert")

        // Wait for the error to propagate and revert
        viewModel.playlists.test {
            // Consume items until name reverts
            val states = mutableListOf<List<com.adagiostream.android.service.navidrome.NavidromePlaylist>>()
            repeat(5) {
                states.add(awaitItem())
                if (states.last().any { it.id == original.id && it.name == originalName }) return@test
            }
            cancelAndIgnoreRemainingEvents()
        }

        val reverted = viewModel.playlists.value.firstOrNull { it.id == original.id }
        assertEquals(
            "Playlist name should revert to '$originalName' after API error",
            originalName,
            reverted?.name,
        )
    }

    // -------------------------------------------------------------------------
    // deletePlaylist — optimistic removal + rollback (baw.4.3)
    // -------------------------------------------------------------------------

    @Test
    fun `deletePlaylist removes playlist immediately (optimistic)`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, viewModel.playlists.value.size)
        val toDelete = viewModel.playlists.value.first()
        server.enqueue(statusOk())

        val preexisting = viewModel.viewModelScope.coroutineContext.job.children.toSet()

        viewModel.deletePlaylist(toDelete)

        // Optimistic removal
        assertEquals(1, viewModel.playlists.value.size)
        assertTrue(viewModel.playlists.value.none { it.id == toDelete.id })

        // Drain the fire-and-forget deletePlaylist coroutine (see the rename
        // optimistic test) so it can't poison a later test after resetMain.
        (viewModel.viewModelScope.coroutineContext.job.children.toSet() - preexisting)
            .forEach { it.join() }
    }

    @Test
    fun `deletePlaylist reverts on API error`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(PLAYLISTS_OK_FIXTURE))
        viewModel.playlistsState.test {
            assertEquals(NavidromePlaylistViewModel.LoadState.Idle, awaitItem())
            viewModel.loadPlaylists()
            awaitItem() // Loading
            awaitItem() // Loaded
            cancelAndIgnoreRemainingEvents()
        }

        val originalSize = viewModel.playlists.value.size
        val toDelete = viewModel.playlists.value.first()

        // Server returns an error
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Not found"}}}"""),
        )

        viewModel.deletePlaylist(toDelete)

        // Wait for revert
        viewModel.playlists.test {
            val states = mutableListOf<List<com.adagiostream.android.service.navidrome.NavidromePlaylist>>()
            repeat(5) {
                states.add(awaitItem())
                if (states.last().size == originalSize) return@test
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            "Playlist list should revert to original size after API error",
            originalSize,
            viewModel.playlists.value.size,
        )
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    companion object {
        private const val PLAYLISTS_OK_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlists": {
                  "playlist": [
                    {"id":"p1","name":"My Mix","songCount":10,"coverArt":"pl-p1"},
                    {"id":"p2","name":"Workout Jams","songCount":25}
                  ]
                }
              }
            }
        """

        private const val PLAYLISTS_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlists": {}
              }
            }
        """

        private const val PLAYLISTS_AFTER_CREATE_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlists": {
                  "playlist": [
                    {"id":"p1","name":"My Mix","songCount":10},
                    {"id":"p2","name":"Workout Jams","songCount":25},
                    {"id":"p3","name":"Brand New Mix","songCount":0}
                  ]
                }
              }
            }
        """

        private const val PLAYLIST_DETAIL_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlist": {
                  "id": "p1",
                  "name": "My Mix",
                  "songCount": 2,
                  "entry": [
                    {"id":"s1","albumId":"al1","artistId":"ar1","title":"Comfortably Numb","artist":"Pink Floyd","track":1,"duration":382},
                    {"id":"s2","albumId":"al1","artistId":"ar1","title":"Wish You Were Here","artist":"Pink Floyd","track":2,"duration":315}
                  ]
                }
              }
            }
        """
    }
}
