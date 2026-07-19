package com.adagiostream.android.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.AlbumListType
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.navidrome.SubsonicGenre
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Navidrome music library browse UI (baw.2.3).
 *
 * Drives three nested screens:
 *  - [MusicLibraryScreen] — artist list (root)
 *  - [ArtistDetailScreen] — albums for a selected artist
 *  - [AlbumDetailScreen]  — tracks for a selected album
 *
 * State model: each screen has its own [LoadState] flow so navigation state is
 * preserved independently.  All network calls live-browse the [NavidromeApi];
 * no Room cache is used (library caching is a follow-on bead).
 *
 * The API is resolved lazily on first access by watching [AccountManager.accounts]
 * for the first enabled [AccountType.Subsonic] entry.  If no Subsonic account is
 * configured the [api] flow remains `null`, and callers render the empty state.
 */
@HiltViewModel
class NavidromeLibraryViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val navidromeApiFactory: NavidromeApiFactory,
    private val musicPlaybackCoordinator: MusicPlaybackCoordinator,
    private val persistenceService: PersistenceService,
    private val libraryRepository: MusicLibraryRepository,
) : ViewModel() {

    // -------------------------------------------------------------------------
    // LoadState — mirrors the iOS NavidromeLibraryViewModel pattern
    // -------------------------------------------------------------------------

    /**
     * Loading state for each browse level.
     *
     * Transitions: Idle → Loading → Loaded | Empty | Error(msg)
     * Retry: reset to Idle then call the relevant load function.
     */
    sealed class LoadState {
        /** Initial state before the first load has been requested. */
        object Idle : LoadState()

        /** A network request is in-flight. */
        object Loading : LoadState()

        /** Data loaded successfully and the list is non-empty. */
        object Loaded : LoadState()

        /** Data loaded successfully but the list is empty. */
        object Empty : LoadState()

        /**
         * The load failed.  [message] is a user-facing string derived from
         * [NavidromeApiException] — never the raw exception message which may
         * expose server internals.
         */
        data class Error(val message: String) : LoadState()
    }

    // -------------------------------------------------------------------------
    // Resolved API (null until a Subsonic account is found)
    // -------------------------------------------------------------------------

    private val _api = MutableStateFlow<NavidromeApi?>(null)

    /** The active [NavidromeApi], or `null` if no Subsonic account is configured. */
    val api: StateFlow<NavidromeApi?> = _api.asStateFlow()

    private val _audiobooksAvailable = MutableStateFlow(false)

    /**
     * Whether an enabled Audiobookshelf account exists (beads_adagio-59p.1.4)
     * — gates the "Audiobooks" browse row on the Library tab, mirroring how
     * the Navidrome browse gates on a Subsonic account.
     */
    val audiobooksAvailable: StateFlow<Boolean> = _audiobooksAvailable.asStateFlow()

    init {
        // Watch for account changes (add/remove/edit) and rebuild the API.
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                val subsonic = accounts
                    .filter { it.isEnabled }
                    .firstNotNullOfOrNull { it.type as? AccountType.Subsonic }

                _api.value = subsonic?.let { s ->
                    navidromeApiFactory.create(s.host, s.username, s.password)
                }

                _audiobooksAvailable.value = accounts.any {
                    it.isEnabled && it.type is AccountType.Audiobookshelf
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Offline mode (baw.12)
    // -------------------------------------------------------------------------

    private val _offlineMode = MutableStateFlow(false)

    /** Whether Offline Mode is on — the Music tab shows downloads only and fires no network requests. */
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    private val _downloadedTracks = MutableStateFlow<List<Track>>(emptyList())

    /** Completed downloads as playable [Track]s, newest first — the offline library listing. */
    val downloadedTracks: StateFlow<List<Track>> = _downloadedTracks.asStateFlow()

    init {
        viewModelScope.launch {
            persistenceService.settings.collect { _offlineMode.value = it.offlineMode }
        }
        // Refresh the offline listing whenever the completed-download index changes.
        viewModelScope.launch {
            libraryRepository.observeCompletedDownloads().collect {
                _downloadedTracks.value = libraryRepository.downloadedTracks()
            }
        }
    }

    /**
     * The API for network browse/search — `null` when Offline Mode is on, so
     * every `load*` function below no-ops without firing a request (baw.12).
     * Playback intentionally bypasses this gate: downloaded tracks resolve to
     * local files via the coordinator's DownloadLocator.
     */
    private fun browseApi(): NavidromeApi? = if (_offlineMode.value) null else _api.value

    // -------------------------------------------------------------------------
    // Artist list state
    // -------------------------------------------------------------------------

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _artistsState = MutableStateFlow<LoadState>(LoadState.Idle)
    val artistsState: StateFlow<LoadState> = _artistsState.asStateFlow()

    /**
     * Loads all artists from the active Navidrome API.
     *
     * Guards against concurrent loads.  If no API is configured the state
     * stays [LoadState.Idle] (the screen renders the "no account" empty state).
     */
    fun loadArtists() {
        val api = browseApi() ?: return
        if (_artistsState.value is LoadState.Loading) return

        viewModelScope.launch {
            _artistsState.value = LoadState.Loading
            try {
                val loaded = api.getArtists()
                    .sortedBy { it.name.lowercase() }
                _artists.value = loaded
                _artistsState.value = if (loaded.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _artistsState.value = LoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _artistsState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Resets artist state to [LoadState.Idle] so the screen can trigger a fresh load. */
    fun retryArtists() {
        _artistsState.value = LoadState.Idle
        loadArtists()
    }

    // -------------------------------------------------------------------------
    // Artist detail (albums) state
    // -------------------------------------------------------------------------

    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    private val _artistAlbums = MutableStateFlow<List<Album>>(emptyList())
    val artistAlbums: StateFlow<List<Album>> = _artistAlbums.asStateFlow()

    private val _albumsState = MutableStateFlow<LoadState>(LoadState.Idle)
    val albumsState: StateFlow<LoadState> = _albumsState.asStateFlow()

    /**
     * Loads albums for [artist] from `getArtist`.
     *
     * No-ops when [artist] is the same as the currently loaded artist (avoids
     * redundant reloads during recomposition).  Guards against concurrent loads.
     */
    fun loadAlbums(artist: Artist) {
        // Skip if already loaded for this artist
        if (_selectedArtist.value?.id == artist.id && _albumsState.value == LoadState.Loaded) return
        val api = browseApi() ?: return
        if (_albumsState.value is LoadState.Loading) return

        viewModelScope.launch {
            _selectedArtist.value = artist
            _artistAlbums.value = emptyList()
            _albumsState.value = LoadState.Loading
            try {
                val (_, albums) = api.getArtist(artist.id)
                val sorted = albums.sortedWith(
                    compareBy({ it.year ?: Int.MAX_VALUE }, { it.title.lowercase() }),
                )
                _artistAlbums.value = sorted
                _albumsState.value = if (sorted.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _albumsState.value = LoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _albumsState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retryAlbums() {
        val artist = _selectedArtist.value ?: return
        _albumsState.value = LoadState.Idle
        loadAlbums(artist)
    }

    // -------------------------------------------------------------------------
    // Album detail (tracks) state
    // -------------------------------------------------------------------------

    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    /**
     * Human-readable artist display name from the `getAlbum` response.
     *
     * Always sourced from the Subsonic `"artist"` string field — never from
     * `artistId`.  The iOS bug that showed `artistId` in the now-playing subtitle
     * came from not threading this field through the ViewModel.
     */
    private val _selectedAlbumArtistName = MutableStateFlow<String?>(null)
    val selectedAlbumArtistName: StateFlow<String?> = _selectedAlbumArtistName.asStateFlow()

    private val _albumTracks = MutableStateFlow<List<Track>>(emptyList())
    val albumTracks: StateFlow<List<Track>> = _albumTracks.asStateFlow()

    private val _tracksState = MutableStateFlow<LoadState>(LoadState.Idle)
    val tracksState: StateFlow<LoadState> = _tracksState.asStateFlow()

    /**
     * Loads tracks for [album] from `getAlbum`.
     *
     * No-ops if this album is already loaded.  Guards against concurrent loads.
     */
    fun loadTracks(album: Album) {
        if (_selectedAlbum.value?.id == album.id && _tracksState.value == LoadState.Loaded) return
        val api = browseApi() ?: return
        if (_tracksState.value is LoadState.Loading) return

        viewModelScope.launch {
            _selectedAlbum.value = album
            _albumTracks.value = emptyList()
            _selectedAlbumArtistName.value = null
            _tracksState.value = LoadState.Loading
            try {
                val (_, artistName, tracks) = api.getAlbum(album.id)
                _selectedAlbumArtistName.value = artistName
                _albumTracks.value = tracks.sortedWith(
                    compareBy({ it.discNumber }, { it.trackNumber ?: Int.MAX_VALUE }),
                )
                _tracksState.value = if (tracks.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _tracksState.value = LoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _tracksState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retryTracks() {
        val album = _selectedAlbum.value ?: return
        _tracksState.value = LoadState.Idle
        loadTracks(album)
    }

    // -------------------------------------------------------------------------
    // Browse → play bridge (baw.3.8)
    // -------------------------------------------------------------------------

    /**
     * Starts playback from a tapped track (baw.3.8).
     *
     * PO decision: tapping a track enqueues the WHOLE loaded album and starts from
     * the tapped index, so auto-advance works immediately. No-op when no Subsonic
     * API is configured or the track is not in the loaded list.
     */
    fun playTrack(track: Track) {
        val api = _api.value ?: return
        val tracks = _albumTracks.value
        val startIndex = tracks.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        musicPlaybackCoordinator.playAlbum(
            tracks = tracks,
            startIndex = startIndex,
            api = api,
            albumTitle = _selectedAlbum.value?.title,
        )
    }

    /**
     * Starts playback from a tapped track in the offline (downloads-only) list
     * (baw.12). Enqueues the WHOLE downloaded list from the tapped index —
     * mirrors [playTrack]. Downloaded tracks resolve to local files via the
     * coordinator's DownloadLocator, so no network is touched for audio and no
     * configured account is required (api may be null — baw.17).
     */
    fun playDownloadedTrack(track: Track) {
        val tracks = _downloadedTracks.value
        val startIndex = tracks.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        musicPlaybackCoordinator.playAlbum(
            tracks = tracks,
            startIndex = startIndex,
            api = _api.value,
            albumTitle = "Downloads",
        )
    }

    // -------------------------------------------------------------------------
    // Album browse list (baw.9.1) — getAlbumList2 with a selectable ordering.
    // -------------------------------------------------------------------------

    private val _albumListType = MutableStateFlow(AlbumListType.NEWEST)
    val albumListType: StateFlow<AlbumListType> = _albumListType.asStateFlow()

    private val _albumBrowseList = MutableStateFlow<List<Album>>(emptyList())
    val albumBrowseList: StateFlow<List<Album>> = _albumBrowseList.asStateFlow()

    private val _albumBrowseState = MutableStateFlow<LoadState>(LoadState.Idle)
    val albumBrowseState: StateFlow<LoadState> = _albumBrowseState.asStateFlow()

    /**
     * Loads a page of albums from `getAlbumList2` ordered by [type] (baw.9.1).
     *
     * Guards against concurrent loads. Callers switching the list-type picker
     * should use [selectAlbumListType] instead, which resets state to [LoadState.Idle]
     * first so the load is never treated as a no-op duplicate request.
     */
    fun loadAlbumBrowseList(type: AlbumListType = _albumListType.value) {
        val api = browseApi() ?: return
        if (_albumBrowseState.value is LoadState.Loading) return

        viewModelScope.launch {
            _albumListType.value = type
            _albumBrowseState.value = LoadState.Loading
            try {
                val loaded = api.getAlbumList2(type = type)
                _albumBrowseList.value = loaded
                _albumBrowseState.value = if (loaded.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _albumBrowseState.value = LoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _albumBrowseState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Resets browse state to [LoadState.Idle] so the screen can trigger a fresh load. */
    fun retryAlbumBrowseList() {
        _albumBrowseState.value = LoadState.Idle
        loadAlbumBrowseList()
    }

    /** Switches the list-type picker to [type] and reloads (baw.9.1). */
    fun selectAlbumListType(type: AlbumListType) {
        _albumBrowseState.value = LoadState.Idle
        loadAlbumBrowseList(type)
    }

    // -------------------------------------------------------------------------
    // Genre browse state (baw.2.4)
    // -------------------------------------------------------------------------

    private val _genres = MutableStateFlow<List<SubsonicGenre>>(emptyList())
    val genres: StateFlow<List<SubsonicGenre>> = _genres.asStateFlow()

    private val _genresState = MutableStateFlow<LoadState>(LoadState.Idle)
    val genresState: StateFlow<LoadState> = _genresState.asStateFlow()

    /**
     * Loads all genres from the active Navidrome API.
     *
     * Results are sorted alphabetically by name.  Guards against concurrent loads.
     */
    fun loadGenres() {
        val api = browseApi() ?: return
        if (_genresState.value is LoadState.Loading) return

        viewModelScope.launch {
            _genresState.value = LoadState.Loading
            try {
                val loaded = api.getGenres()
                    .sortedBy { it.name.lowercase() }
                _genres.value = loaded
                _genresState.value = if (loaded.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _genresState.value = LoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _genresState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Resets genre state to [LoadState.Idle] so the screen can trigger a fresh load. */
    fun retryGenres() {
        _genresState.value = LoadState.Idle
        loadGenres()
    }

    // -------------------------------------------------------------------------
    // Genre detail (songs by genre) state (baw.2.4)
    // -------------------------------------------------------------------------

    private val _selectedGenre = MutableStateFlow<SubsonicGenre?>(null)
    val selectedGenre: StateFlow<SubsonicGenre?> = _selectedGenre.asStateFlow()

    private val _genreTracks = MutableStateFlow<List<Track>>(emptyList())
    val genreTracks: StateFlow<List<Track>> = _genreTracks.asStateFlow()

    private val _genreTracksState = MutableStateFlow<LoadState>(LoadState.Idle)
    val genreTracksState: StateFlow<LoadState> = _genreTracksState.asStateFlow()

    /**
     * Loads songs for [genre] from `getSongsByGenre`.
     *
     * Results are sorted alphabetically by title.  No-ops when [genre] is the
     * same as the currently loaded genre (avoids redundant reloads on recomposition).
     * Guards against concurrent loads.
     */
    fun loadSongsByGenre(genre: SubsonicGenre) {
        if (_selectedGenre.value?.name == genre.name && _genreTracksState.value == LoadState.Loaded) return
        val api = browseApi() ?: return
        if (_genreTracksState.value is LoadState.Loading) return

        viewModelScope.launch {
            _selectedGenre.value = genre
            _genreTracks.value = emptyList()
            _genreTracksState.value = LoadState.Loading
            try {
                val loaded = api.getSongsByGenre(genre.name)
                    .sortedBy { it.title.lowercase() }
                _genreTracks.value = loaded
                _genreTracksState.value = if (loaded.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _genreTracksState.value = LoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _genreTracksState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retryGenreTracks() {
        val genre = _selectedGenre.value ?: return
        _genreTracksState.value = LoadState.Idle
        loadSongsByGenre(genre)
    }

    // -------------------------------------------------------------------------
    // Star / unstar (baw.5.2)
    // -------------------------------------------------------------------------

    /**
     * Toggles the starred state of [track] in Navidrome (baw.5.2).
     *
     * Performs an optimistic UI update — flips [Track.starred] immediately in
     * the in-memory state, then calls [NavidromeApi.star] or [NavidromeApi.unstar].
     * On error the update is reverted.
     *
     * Architecturally separate from IPTV favourite IDs ([toggleFavorite] /
     * [favoriteIds]): stars are a Navidrome-only concept, stored server-side.
     */
    fun toggleStar(track: Track) {
        val api = browseApi() ?: return
        val newStarred = !(track.starred ?: false)
        val updated = track.copy(starred = newStarred)

        // Optimistic update across all in-memory track lists.
        updateTrackInLists(updated)

        viewModelScope.launch {
            try {
                if (newStarred) api.star(track.id) else api.unstar(track.id)
            } catch (_: Exception) {
                // Revert optimistic update on error.
                updateTrackInLists(track)
            }
        }
    }

    /** Replaces the track with matching id in all in-memory track lists. */
    private fun updateTrackInLists(updated: Track) {
        _albumTracks.value = _albumTracks.value.map { if (it.id == updated.id) updated else it }
        _genreTracks.value = _genreTracks.value.map { if (it.id == updated.id) updated else it }
    }

    // -------------------------------------------------------------------------
    // Genre browse → play bridge (baw.2.4 + baw.3.8)
    // -------------------------------------------------------------------------

    /**
     * Starts playback from a tapped genre track (baw.2.4 + baw.3.8).
     *
     * Mirrors [playTrack] but operates on the [_genreTracks] queue.
     * Tapping a track enqueues the WHOLE loaded genre track list and starts
     * from the tapped index, so auto-advance works immediately.
     */
    fun playGenreTrack(track: Track) {
        val api = _api.value ?: return
        val tracks = _genreTracks.value
        val startIndex = tracks.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        musicPlaybackCoordinator.playAlbum(
            tracks = tracks,
            startIndex = startIndex,
            api = api,
            albumTitle = _selectedGenre.value?.name,
        )
    }
}

// -------------------------------------------------------------------------
// NavidromeApiException user-facing message
// -------------------------------------------------------------------------

/**
 * Maps a [NavidromeApiException] to a short user-facing message suitable for
 * display in error states.  Never leaks internal server details.
 */
private val NavidromeApiException.userMessage: String
    get() = when (this) {
        is NavidromeApiException.AuthFailed ->
            "Authentication failed. Check your username and password in Settings → Accounts."
        is NavidromeApiException.Unreachable ->
            "Cannot reach the server. Check your network and server address."
        is NavidromeApiException.TimedOut ->
            "The request timed out. Check your network connection."
        is NavidromeApiException.ServerError ->
            "Server error (HTTP $statusCode). Try again later."
        is NavidromeApiException.NotSubsonicServer ->
            "The server didn't respond as expected. Verify the server URL in Settings."
        is NavidromeApiException.SubsonicError ->
            message ?: "Subsonic error $code"
        is NavidromeApiException.InvalidUrl ->
            "Invalid server URL. Check the address in Settings → Accounts."
        is NavidromeApiException.DecodingError ->
            "Unexpected server response. The server may need updating."
    }
