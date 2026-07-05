package com.adagiostream.android.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.navidrome.NavidromePlaylist
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Navidrome playlist browse, play, and editing (baw.4.2 / baw.4.3).
 *
 * Drives:
 *  - [NavidromePlaylistListScreen] — list of all server-side playlists.
 *  - [NavidromePlaylistDetailScreen] — tracks for one playlist + "Play All".
 *  - [AddToPlaylistSheet] — inline "add to playlist" from album/genre/search rows.
 *
 * NAMING: [NavidromePlaylist] is intentionally distinct from the IPTV M3U
 * [CustomPlaylist] system.  These two playlist concepts are entirely separate
 * and must never be mixed.
 *
 * CRUD (baw.4.3):
 *  - All write operations are optimistic — local state updates immediately.
 *  - On API error the local state is reverted to the previous value.
 *  - Failed creates are dropped (no local state was added, so no revert needed).
 */
@HiltViewModel
class NavidromePlaylistViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val navidromeApiFactory: NavidromeApiFactory,
    private val musicPlaybackCoordinator: MusicPlaybackCoordinator,
) : ViewModel() {

    // -------------------------------------------------------------------------
    // LoadState — same shape as NavidromeLibraryViewModel.LoadState
    // -------------------------------------------------------------------------

    sealed class LoadState {
        object Idle : LoadState()
        object Loading : LoadState()
        object Loaded : LoadState()
        object Empty : LoadState()
        data class Error(val message: String) : LoadState()
    }

    // -------------------------------------------------------------------------
    // API resolution
    // -------------------------------------------------------------------------

    private val _api = MutableStateFlow<NavidromeApi?>(null)
    val api: StateFlow<NavidromeApi?> = _api.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                val subsonic = accounts
                    .filter { it.isEnabled }
                    .firstNotNullOfOrNull { it.type as? AccountType.Subsonic }
                _api.value = subsonic?.let { s ->
                    navidromeApiFactory.create(s.host, s.username, s.password)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Playlist list state
    // -------------------------------------------------------------------------

    private val _playlists = MutableStateFlow<List<NavidromePlaylist>>(emptyList())
    val playlists: StateFlow<List<NavidromePlaylist>> = _playlists.asStateFlow()

    private val _playlistsState = MutableStateFlow<LoadState>(LoadState.Idle)
    val playlistsState: StateFlow<LoadState> = _playlistsState.asStateFlow()

    /**
     * Loads all playlists from the active Navidrome API.
     *
     * Results are sorted alphabetically by name.  Guards against concurrent loads.
     */
    fun loadPlaylists() {
        val currentApi = _api.value ?: return
        if (_playlistsState.value is LoadState.Loading) return

        viewModelScope.launch {
            _playlistsState.value = LoadState.Loading
            try {
                val loaded = currentApi.getPlaylists()
                    .sortedBy { it.name.lowercase() }
                _playlists.value = loaded
                _playlistsState.value = if (loaded.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _playlistsState.value = LoadState.Error(e.playlistUserMessage)
            } catch (e: Exception) {
                _playlistsState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retryPlaylists() {
        _playlistsState.value = LoadState.Idle
        loadPlaylists()
    }

    // -------------------------------------------------------------------------
    // Playlist detail state
    // -------------------------------------------------------------------------

    private val _selectedPlaylist = MutableStateFlow<NavidromePlaylist?>(null)
    val selectedPlaylist: StateFlow<NavidromePlaylist?> = _selectedPlaylist.asStateFlow()

    private val _playlistTracks = MutableStateFlow<List<Track>>(emptyList())
    val playlistTracks: StateFlow<List<Track>> = _playlistTracks.asStateFlow()

    private val _playlistDetailState = MutableStateFlow<LoadState>(LoadState.Idle)
    val playlistDetailState: StateFlow<LoadState> = _playlistDetailState.asStateFlow()

    /**
     * Loads tracks for [playlist] from `getPlaylist`.
     *
     * No-ops when [playlist] is already the loaded playlist (avoids redundant
     * reloads on recomposition).  Guards against concurrent loads.
     */
    fun loadPlaylistDetail(playlist: NavidromePlaylist) {
        if (_selectedPlaylist.value?.id == playlist.id &&
            _playlistDetailState.value == LoadState.Loaded
        ) return
        val currentApi = _api.value ?: return
        if (_playlistDetailState.value is LoadState.Loading) return

        viewModelScope.launch {
            _selectedPlaylist.value = playlist
            _playlistTracks.value = emptyList()
            _playlistDetailState.value = LoadState.Loading
            try {
                val (updatedPlaylist, tracks) = currentApi.getPlaylist(playlist.id)
                _selectedPlaylist.value = updatedPlaylist
                _playlistTracks.value = tracks
                _playlistDetailState.value =
                    if (tracks.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (e: NavidromeApiException) {
                _playlistDetailState.value = LoadState.Error(e.playlistUserMessage)
            } catch (e: Exception) {
                _playlistDetailState.value = LoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retryPlaylistDetail() {
        val playlist = _selectedPlaylist.value ?: return
        _playlistDetailState.value = LoadState.Idle
        loadPlaylistDetail(playlist)
    }

    // -------------------------------------------------------------------------
    // Playlist → play bridge (baw.4.2 + E3 coordinator reuse)
    // -------------------------------------------------------------------------

    /**
     * Starts playback from a tapped playlist track (baw.4.2).
     *
     * Enqueues the WHOLE playlist track list and starts from the tapped index,
     * so auto-advance works immediately.  Mirrors [NavidromeLibraryViewModel.playTrack].
     *
     * No-op when no API is configured or the track is not in the current list.
     */
    fun playPlaylistTrack(track: Track) {
        val currentApi = _api.value ?: return
        val tracks = _playlistTracks.value
        val startIndex = tracks.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        musicPlaybackCoordinator.playAlbum(
            tracks = tracks,
            startIndex = startIndex,
            api = currentApi,
            albumTitle = _selectedPlaylist.value?.name,
        )
    }

    /** Plays the playlist from the first track. */
    fun playAllPlaylistTracks() {
        val first = _playlistTracks.value.firstOrNull() ?: return
        playPlaylistTrack(first)
    }

    // -------------------------------------------------------------------------
    // Playlist CRUD (baw.4.3)
    // -------------------------------------------------------------------------

    /**
     * Creates a new empty playlist with [name] (baw.4.3).
     *
     * On success, refreshes the full playlist list from the server.
     * Failed creates are dropped silently (no local state was optimistically added).
     */
    fun createPlaylist(name: String) {
        val currentApi = _api.value ?: return
        viewModelScope.launch {
            try {
                currentApi.createPlaylist(name)
                // Refresh list from server so the new playlist appears with its real id.
                loadPlaylistsInternal(currentApi)
            } catch (_: Exception) {
                // Failed create — no local state to revert.
            }
        }
    }

    /**
     * Renames [playlist] to [newName] with an optimistic local update (baw.4.3).
     *
     * On API error, reverts the rename in both the playlists list and the
     * selected-playlist state.
     */
    fun renamePlaylist(playlist: NavidromePlaylist, newName: String) {
        val currentApi = _api.value ?: return
        // Snapshot for revert
        val oldList = _playlists.value
        val oldSelected = _selectedPlaylist.value

        // Optimistic update
        val renamed = playlist.copy(name = newName)
        _playlists.value = _playlists.value
            .map { if (it.id == playlist.id) renamed else it }
            .sortedBy { it.name.lowercase() }
        if (_selectedPlaylist.value?.id == playlist.id) {
            _selectedPlaylist.value = renamed
        }

        viewModelScope.launch {
            try {
                currentApi.updatePlaylist(id = playlist.id, name = newName)
            } catch (_: Exception) {
                // Revert optimistic update
                _playlists.value = oldList
                _selectedPlaylist.value = oldSelected
            }
        }
    }

    /**
     * Deletes [playlist] with an optimistic local removal (baw.4.3).
     *
     * On API error, puts the playlist back and restores [LoadState.Loaded].
     */
    fun deletePlaylist(playlist: NavidromePlaylist) {
        val currentApi = _api.value ?: return
        // Snapshot for revert
        val oldList = _playlists.value
        val oldState = _playlistsState.value

        // Optimistic removal
        _playlists.value = _playlists.value.filter { it.id != playlist.id }
        if (_playlists.value.isEmpty()) {
            _playlistsState.value = LoadState.Empty
        }

        viewModelScope.launch {
            try {
                currentApi.deletePlaylist(playlist.id)
            } catch (_: Exception) {
                // Revert
                _playlists.value = oldList
                _playlistsState.value = oldState
            }
        }
    }

    /**
     * Adds [trackId] to playlist [playlistId] via a `updatePlaylist` call (baw.4.3).
     *
     * Fire-and-mostly-forget: errors are suppressed because no local state was
     * optimistically modified.  The caller may choose to show a snackbar on error
     * by observing a dedicated error flow if needed in a future iteration.
     */
    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val currentApi = _api.value ?: return
        viewModelScope.launch {
            try {
                currentApi.updatePlaylist(id = playlistId, songIdsToAdd = listOf(trackId))
            } catch (_: Exception) {
                // Suppressed — no local state was changed.
            }
        }
    }

    /**
     * Removes the track at [index] in the currently-displayed [playlistTracks] from
     * [playlist] (baw.9.2).
     *
     * CRITICAL: `updatePlaylist`'s `songIndexToRemove` param is INDEX-based, not
     * song-ID-based. With duplicate tracks in a playlist (the same song appearing
     * twice), removing by ID would be ambiguous — the server has no way to know
     * which occurrence to drop. This method always sends the exact 0-based [index]
     * of the row the user tapped/swiped in the list currently on screen, so
     * duplicates are handled correctly regardless of which occurrence is removed.
     *
     * Optimistically removes the row locally, then — once the server call
     * succeeds — refreshes the playlist from the server so the client converges
     * on canonical state (e.g. if another client edited the playlist concurrently).
     * On API failure the optimistic removal is reverted.
     */
    fun removeTrackAt(playlist: NavidromePlaylist, index: Int) {
        val currentApi = _api.value ?: return
        val oldTracks = _playlistTracks.value
        if (index !in oldTracks.indices) return

        _playlistTracks.value = oldTracks.toMutableList().apply { removeAt(index) }

        viewModelScope.launch {
            try {
                currentApi.updatePlaylist(id = playlist.id, indicesToRemove = listOf(index))
                // Refresh from server afterward for canonical state (baw.9.2).
                val (updatedPlaylist, tracks) = currentApi.getPlaylist(playlist.id)
                _selectedPlaylist.value = updatedPlaylist
                _playlistTracks.value = tracks
                _playlistDetailState.value = if (tracks.isEmpty()) LoadState.Empty else LoadState.Loaded
            } catch (_: Exception) {
                // Revert optimistic removal.
                _playlistTracks.value = oldTracks
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Internal load that uses an already-resolved [api] to avoid null check duplication. */
    private suspend fun loadPlaylistsInternal(currentApi: NavidromeApi) {
        _playlistsState.value = LoadState.Loading
        try {
            val loaded = currentApi.getPlaylists()
                .sortedBy { it.name.lowercase() }
            _playlists.value = loaded
            _playlistsState.value = if (loaded.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (e: NavidromeApiException) {
            _playlistsState.value = LoadState.Error(e.playlistUserMessage)
        } catch (e: Exception) {
            _playlistsState.value = LoadState.Error(e.message ?: "Unknown error")
        }
    }
}

// -------------------------------------------------------------------------
// NavidromeApiException user-facing message (playlist scope)
// -------------------------------------------------------------------------

private val NavidromeApiException.playlistUserMessage: String
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
