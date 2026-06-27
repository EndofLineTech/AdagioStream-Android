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

    // Records what the browse→play bridge would start (baw.3.8).
    private val playedSources = mutableListOf<PlaybackSource.Library>()
    private val fakeTrackPlayer = object : LibraryTrackPlayer {
        override fun playLibraryTrack(streamUrl: String, source: PlaybackSource.Library) {
            playedSources += source
        }
        override fun stop() {}
    }

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
            musicPlaybackCoordinator = MusicPlaybackCoordinator(MusicQueueManager(), fakeTrackPlayer),
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
    // Browse → play bridge (baw.3.8)
    // -------------------------------------------------------------------------

    @Test
    fun `playTrack enqueues the whole album and starts from the tapped index`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(ALBUM_DETAIL_FIXTURE))

        val fakeAlbum = com.adagiostream.android.service.navidrome.Album(
            id = "al1", artistId = "ar1", title = "OK Computer", updatedAt = 0,
        )
        viewModel.tracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadTracks(fakeAlbum)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val tracks = viewModel.albumTracks.value
        assertEquals(3, tracks.size)
        // Tap the 2nd track (Paranoid Android, t2).
        viewModel.playTrack(tracks[1])

        assertEquals(1, playedSources.size)
        val source = playedSources.first()
        assertEquals(3, source.queue.size)          // whole album enqueued
        assertEquals(1, source.index)               // started from tapped index
        assertEquals("t2", source.currentTrack.id)
    }

    @Test
    fun `playTrack is a no-op when no Subsonic account is configured`() = runTest {
        // No account → no api. Calling playTrack must not start anything.
        viewModel.playTrack(com.adagiostream.android.testutil.TestFixtures.makeTrack())
        assertTrue(playedSources.isEmpty())
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
    // Genre browse (baw.2.4) — loadGenres state machine
    // -------------------------------------------------------------------------

    @Test
    fun `initial genresState is Idle`() = runTest {
        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGenres transitions Idle then Loading then Loaded on success`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRES_OK_FIXTURE))

        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            assertEquals(NavidromeLibraryViewModel.LoadState.Loading, awaitItem())
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGenres populates genres list sorted alphabetically by name`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRES_OK_FIXTURE))

        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val genres = viewModel.genres.value
        assertEquals(3, genres.size)
        // Sorted alphabetically: Classical, Jazz, Rock
        assertEquals("Classical", genres[0].name)
        assertEquals("Jazz", genres[1].name)
        assertEquals("Rock", genres[2].name)
    }

    @Test
    fun `loadGenres transitions to Empty when server returns no genres`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRES_EMPTY_FIXTURE))

        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGenres transitions to Error on auth failure`() = runTest {
        setSubsonicAccount()
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong credentials"}}}"""),
        )

        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            awaitItem() // Loading
            val result = awaitItem()
            assertTrue("Expected Error state", result is NavidromeLibraryViewModel.LoadState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGenres is no-op when no Subsonic account configured`() = runTest {
        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGenres is no-op while already Loading`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRES_OK_FIXTURE))

        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            awaitItem() // Loading
            viewModel.loadGenres() // second call — no-op
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retryGenres resets to Idle then triggers fresh load`() = runTest {
        setSubsonicAccount()
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Auth error"}}}"""),
        )
        server.enqueue(mockOk(GENRES_OK_FIXTURE))

        viewModel.genresState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadGenres()
            awaitItem() // Loading
            val errorState = awaitItem()
            assertTrue("Should be Error state", errorState is NavidromeLibraryViewModel.LoadState.Error)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.genresState.test {
            viewModel.retryGenres()
            val states = mutableListOf<NavidromeLibraryViewModel.LoadState>()
            repeat(5) {
                states.add(awaitItem())
                if (states.last() == NavidromeLibraryViewModel.LoadState.Loaded) return@test
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, viewModel.genresState.value)
    }

    // -------------------------------------------------------------------------
    // Genre detail — loadSongsByGenre state machine (baw.2.4)
    // -------------------------------------------------------------------------

    @Test
    fun `loadSongsByGenre transitions to Loaded and populates genreTracks`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRE_SONGS_FIXTURE))

        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Rock", songCount = 2, albumCount = 1,
        )

        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, viewModel.genreTracks.value.size)
    }

    @Test
    fun `loadSongsByGenre sorts tracks alphabetically by title`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRE_SONGS_FIXTURE))

        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Rock", songCount = 2, albumCount = 1,
        )

        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val tracks = viewModel.genreTracks.value
        assertEquals(2, tracks.size)
        // Alphabetical: "Bohemian Rhapsody" < "We Will Rock You"
        assertEquals("Bohemian Rhapsody", tracks[0].title)
        assertEquals("We Will Rock You", tracks[1].title)
    }

    @Test
    fun `loadSongsByGenre exposes artist NAME not artistId`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRE_SONGS_FIXTURE))

        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Rock", songCount = 2, albumCount = 1,
        )

        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val tracks = viewModel.genreTracks.value
        // artist field must be the human-readable name from "artist" JSON field, never the artistId
        assertTrue(
            "All tracks must have artist name 'Queen' from the 'artist' field, not the artistId 'ar1'",
            tracks.all { it.artist == "Queen" },
        )
    }

    @Test
    fun `loadSongsByGenre transitions to Empty when server returns no songs`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRE_SONGS_EMPTY_FIXTURE))

        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Empty Genre", songCount = 0, albumCount = 0,
        )

        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadSongsByGenre is no-op when same genre already Loaded`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRE_SONGS_FIXTURE))

        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Rock", songCount = 2, albumCount = 1,
        )

        // First load
        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Second call with the same genre — no-op; no new Loading emitted
        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem()) // current state
            viewModel.loadSongsByGenre(fakeGenre)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadSongsByGenre is no-op when no Subsonic account configured`() = runTest {
        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Rock", songCount = 2, albumCount = 1,
        )

        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // Genre browse → play bridge (baw.2.4 + baw.3.8)
    // -------------------------------------------------------------------------

    @Test
    fun `playGenreTrack enqueues genre tracks and starts from tapped index`() = runTest {
        setSubsonicAccount()
        server.enqueue(mockOk(GENRE_SONGS_FIXTURE))

        val fakeGenre = com.adagiostream.android.service.navidrome.SubsonicGenre(
            name = "Rock", songCount = 2, albumCount = 1,
        )

        viewModel.genreTracksState.test {
            assertEquals(NavidromeLibraryViewModel.LoadState.Idle, awaitItem())
            viewModel.loadSongsByGenre(fakeGenre)
            awaitItem() // Loading
            assertEquals(NavidromeLibraryViewModel.LoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val tracks = viewModel.genreTracks.value
        assertEquals(2, tracks.size)
        // Tap the second track (We Will Rock You, sg1 — index 1 after alpha sort)
        viewModel.playGenreTrack(tracks[1])

        assertEquals(1, playedSources.size)
        val source = playedSources.first()
        assertEquals(2, source.queue.size)           // whole genre queue enqueued
        assertEquals(1, source.index)                // started from tapped index
        assertEquals(tracks[1].id, source.currentTrack.id)
    }

    @Test
    fun `playGenreTrack is no-op when no Subsonic account configured`() = runTest {
        viewModel.playGenreTrack(com.adagiostream.android.testutil.TestFixtures.makeTrack())
        assertTrue(playedSources.isEmpty())
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

        // Genre fixtures (baw.2.4)

        private const val GENRES_OK_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "genres": {
                  "genre": [
                    {"value":"Rock","songCount":42,"albumCount":8},
                    {"value":"Jazz","songCount":15,"albumCount":3},
                    {"value":"Classical","songCount":7,"albumCount":2}
                  ]
                }
              }
            }
        """

        private const val GENRES_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "genres": { "genre": [] }
              }
            }
        """

        /**
         * Two Rock songs returned in unsorted order (We Will Rock You first, Bohemian Rhapsody second)
         * so that the sort-by-title test can verify the ViewModel sorts them correctly.
         */
        private const val GENRE_SONGS_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "songsByGenre": {
                  "song": [
                    {"id":"sg1","albumId":"al1","artistId":"ar1","title":"We Will Rock You","artist":"Queen","track":1,"duration":121},
                    {"id":"sg2","albumId":"al1","artistId":"ar1","title":"Bohemian Rhapsody","artist":"Queen","track":2,"duration":355}
                  ]
                }
              }
            }
        """

        private const val GENRE_SONGS_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "songsByGenre": { "song": [] }
              }
            }
        """
    }
}
