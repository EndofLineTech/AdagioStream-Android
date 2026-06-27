package com.adagiostream.android.ui.screens.music

import app.cash.turbine.test
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NavidromeLibraryViewModel state-machine tests using Turbine (baw.2.3).
 *
 * Tests the Idle → Loading → Loaded / Empty / Error transition for each browse
 * level (artists, albums, tracks), plus no-account idle behaviour.
 *
 * Architecture: [AccountRepository] is faked inline with a [MutableStateFlow].
 * [NavidromeApi] is backed by [MockWebServer] so the network layer is exercised
 * without real network I/O.
 */
class NavidromeLibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: NavidromeApi
    private lateinit var viewModel: NavidromeLibraryViewModel

    // Fake AccountRepository — simple MutableStateFlow, no Android dependencies.
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

        val factory = NavidromeApiFactory { host, username, password ->
            NavidromeApi(client = client, host = host, username = username, password = password)
        }

        viewModel = NavidromeLibraryViewModel(
            accountRepository = fakeAccountRepository,
            navidromeApiFactory = factory,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `initial artistsState is Idle`() = runTest {
        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `api is null when no Subsonic account configured`() = runTest {
        viewModel.api.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `api becomes non-null when Subsonic account is added`() = runTest {
        viewModel.api.test {
            assertNull(awaitItem()) // initial: no account
            setSubsonicAccount()
            val resolved = awaitItem()
            assertTrue("api should be non-null after account is set", resolved != null)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // loadArtists — state machine
    // -------------------------------------------------------------------------

    @Test
    fun `loadArtists transitions Idle then Loading then Loaded on success`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ARTISTS_OK_FIXTURE))

        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())

            viewModel.loadArtists()

            val loading = awaitItem()
            assertEquals(NavidromeLibraryViewModel.LoadState.Loading, loading)

            val loaded = awaitItem()
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, loaded)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadArtists populates artists list on success`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ARTISTS_OK_FIXTURE))

        viewModel.loadArtists()

        // Wait for completion
        viewModel.artistsState.test {
            // Consume until Loaded
            val states = mutableListOf<NavidromeLibraryViewModel.LoadState>()
            repeat(5) {
                val s = awaitItem()
                states.add(s)
                if (s == NavidromeLibraryViewModel.LoadState.Loaded) return@test
            }
            cancelAndIgnoreRemainingEvents()
        }

        val artists = viewModel.artists.value
        assertEquals(2, artists.size)
        val names = artists.map { it.name }
        assertTrue(names.contains("Blur"))
        assertTrue(names.contains("Coldplay"))
    }

    @Test
    fun `loadArtists transitions to Empty when server returns no artists`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ARTISTS_EMPTY_FIXTURE))

        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadArtists()
            awaitItem() // Loading
            val result = awaitItem()
            assertEquals(NavidromeLibraryViewModel.LoadState.Empty, result)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadArtists transitions to Error on auth failure`() = runTest {
        setSubsonicAccount()
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong credentials"}}}"""),
        )

        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadArtists()
            awaitItem() // Loading
            val result = awaitItem()
            assertTrue("Expected Error state", result is NavidromeLibraryViewModel.LoadState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadArtists is no-op when no Subsonic account configured`() = runTest {
        // No setSubsonicAccount() call

        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadArtists()
            // Should remain Idle — no state change
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadArtists is no-op while already Loading`() = runTest {
        setSubsonicAccount()
        // First request takes a long time (we won't answer it during the test)
        server.enqueue(mockOk(ARTISTS_OK_FIXTURE))

        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadArtists() // triggers Loading
            awaitItem() // Loading

            // Second call should be a no-op
            viewModel.loadArtists()
            expectNoEvents() // no new Loading emitted

            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // loadAlbums — state machine
    // -------------------------------------------------------------------------

    @Test
    fun `loadAlbums transitions to Loaded and populates artistAlbums`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ARTIST_DETAIL_FIXTURE))

        val fakeArtist = com.adagiostream.android.service.navidrome.Artist(
            id = "ar1", name = "Radiohead", albumCount = 2, updatedAt = 0,
        )

        viewModel.albumsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadAlbums(fakeArtist)
            awaitItem() // Loading
            val result = awaitItem()
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, result)
            cancelAndIgnoreRemainingEvents()
        }

        val albums = viewModel.artistAlbums.value
        assertEquals(2, albums.size)
    }

    @Test
    fun `loadAlbums sorts by year then title`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ARTIST_DETAIL_FIXTURE))

        val fakeArtist = com.adagiostream.android.service.navidrome.Artist(
            id = "ar1", name = "Radiohead", albumCount = 2, updatedAt = 0,
        )
        viewModel.albumsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadAlbums(fakeArtist)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val albums = viewModel.artistAlbums.value
        // 1997 should come before 2000
        assertEquals(1997, albums.first().year)
        assertEquals(2000, albums.last().year)
    }

    // -------------------------------------------------------------------------
    // loadTracks — state machine
    // -------------------------------------------------------------------------

    @Test
    fun `loadTracks transitions to Loaded and exposes artistName`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ALBUM_DETAIL_FIXTURE))

        val fakeAlbum = com.adagiostream.android.service.navidrome.Album(
            id = "al1", artistId = "ar1", title = "OK Computer", updatedAt = 0,
        )

        viewModel.tracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadTracks(fakeAlbum)
            awaitItem() // Loading
            val result = awaitItem()
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, result)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("Radiohead", viewModel.selectedAlbumArtistName.value)
        assertEquals(3, viewModel.albumTracks.value.size)
    }

    @Test
    fun `loadTracks artist display name is from artist field not artistId`() = runTest {
        setSubsonicAccount()
        // Album response with no artist field — artistName must be null
        server.enqueue(
            mockOk(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al2","artistId":"ar1","title":"Kid A","song":[]}}}""",
            ),
        )

        val fakeAlbum = com.adagiostream.android.service.navidrome.Album(
            id = "al2", artistId = "ar1", title = "Kid A", updatedAt = 0,
        )
        viewModel.tracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadTracks(fakeAlbum)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Must be null, not "ar1" (iOS bug was using artistId here)
        assertNull(
            "artistName must be null when server omits 'artist' field, never the artistId",
            viewModel.selectedAlbumArtistName.value,
        )
    }

    // -------------------------------------------------------------------------
    // retryArtists
    // -------------------------------------------------------------------------

    @Test
    fun `retryArtists resets to Idle then triggers fresh load`() = runTest {
        setSubsonicAccount()
        // First load fails
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Auth error"}}}"""),
        )
        // Retry load succeeds
        server.enqueue(mockOk(ARTISTS_OK_FIXTURE))

        // Drive to Error
        viewModel.artistsState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadArtists()
            awaitItem() // Loading
            val errorState = awaitItem()
            assertTrue("Should be Error state", errorState is NavidromeLibraryViewModel.LoadState.Error)
            cancelAndIgnoreRemainingEvents()
        }

        // Retry
        viewModel.artistsState.test {
            viewModel.retryArtists()
            val states = mutableListOf<NavidromeLibraryViewModel.LoadState>()
            repeat(5) {
                states.add(awaitItem())
                if (states.last() == NavidromeLibraryViewModel.LoadState.Loaded) return@test
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, viewModel.artistsState.value)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    companion object {
        private const val ARTISTS_OK_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artists": {
                  "index": [
                    {"name":"B","artist":[{"id":"ar2","name":"Blur","albumCount":7}]},
                    {"name":"C","artist":[{"id":"ar3","name":"Coldplay","albumCount":9}]}
                  ]
                }
              }
            }
        """

        private const val ARTISTS_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artists": { "index": [] }
              }
            }
        """

        private const val ARTIST_DETAIL_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artist": {
                  "id": "ar1",
                  "name": "Radiohead",
                  "albumCount": 2,
                  "album": [
                    {"id":"al2","artistId":"ar1","name":"Kid A","songCount":10,"year":2000},
                    {"id":"al1","artistId":"ar1","name":"OK Computer","songCount":12,"year":1997}
                  ]
                }
              }
            }
        """

        private const val ALBUM_DETAIL_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "album": {
                  "id": "al1",
                  "artistId": "ar1",
                  "artist": "Radiohead",
                  "title": "OK Computer",
                  "year": 1997,
                  "songCount": 3,
                  "song": [
                    {"id":"t1","albumId":"al1","artistId":"ar1","title":"Airbag","track":1,"duration":229},
                    {"id":"t2","albumId":"al1","artistId":"ar1","title":"Paranoid Android","track":2,"duration":387},
                    {"id":"t3","albumId":"al1","artistId":"ar1","title":"Karma Police","track":3,"duration":261}
                  ]
                }
              }
            }
        """
    }
}
