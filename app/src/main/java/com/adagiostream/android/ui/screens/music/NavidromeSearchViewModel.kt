package com.adagiostream.android.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.navidrome.NavidromeSearchResult
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Navidrome full-text search screen (baw.4.1).
 *
 * Drives [SearchResultsScreen] with:
 *  - A 300ms debounced search query via [onQueryChanged].  Blank / whitespace-only
 *    queries are short-circuited before any network call is made.
 *  - Sectioned results: [NavidromeSearchResult] with artists/albums/songs.
 *  - Play bridge: [playSearchTrack] enqueues all song results as a queue and
 *    starts from the tapped index (mirrors the E3 album/genre play bridge).
 *
 * Artist NAME is always sourced from the Subsonic `"artist"` field on each track,
 * never from `artistId` — consistent with the iOS fix and the existing browse
 * screens.
 */
@HiltViewModel
class NavidromeSearchViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val navidromeApiFactory: NavidromeApiFactory,
    private val musicPlaybackCoordinator: MusicPlaybackCoordinator,
    private val persistenceService: PersistenceService,
) : ViewModel() {

    /**
     * The API for network search — `null` when Offline Mode is on (baw.12/baw.17),
     * so searches no-op without firing a request even from a back-stack screen.
     */
    private fun browseApi(): NavidromeApi? =
        if (persistenceService.settings.value.offlineMode) null else _api.value

    // -------------------------------------------------------------------------
    // API resolution (mirrors NavidromeLibraryViewModel pattern)
    // -------------------------------------------------------------------------

    private val _api = MutableStateFlow<NavidromeApi?>(null)

    /** The active [NavidromeApi], or `null` if no Subsonic account is configured. */
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
    // Search state
    // -------------------------------------------------------------------------

    /**
     * Search loading state.
     *
     * Transitions: Idle (no query) → Loading → Loaded | Empty | Error
     * Blank query resets back to Idle immediately (before debounce fires).
     */
    sealed class SearchState {
        /** No query has been entered, or the query was cleared. */
        object Idle : SearchState()

        /** A network request is in-flight. */
        object Loading : SearchState()

        /**
         * Results loaded successfully and at least one artist/album/song was returned.
         * [result] contains the full search result for rendering the sections.
         */
        data class Loaded(val result: NavidromeSearchResult) : SearchState()

        /** Query returned no results across all categories. */
        object Empty : SearchState()

        /** The request failed — [message] is a safe user-facing string. */
        data class Error(val message: String) : SearchState()
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _searchResults = MutableStateFlow<NavidromeSearchResult?>(null)
    val searchResults: StateFlow<NavidromeSearchResult?> = _searchResults.asStateFlow()

    // -------------------------------------------------------------------------
    // Query input — 300ms manual debounce via Job cancellation
    // -------------------------------------------------------------------------

    /**
     * Active search job. Cancelling it when a new query arrives implements
     * debounce: only the last query (after 300ms of no new input) fires.
     */
    private var searchJob: Job? = null

    /**
     * Called whenever the user types in the search field.
     *
     * Implements 300ms debounce by cancelling the previous pending job and
     * starting a new one with a [delay].  Blank / whitespace-only queries
     * short-circuit immediately — state → Idle, no network call.
     */
    fun onQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()

        if (query.isBlank()) {
            _searchState.value = SearchState.Idle
            _searchResults.value = null
            return
        }

        searchJob = viewModelScope.launch {
            delay(300L) // debounce window
            val currentApi = browseApi() ?: return@launch
            _searchState.value = SearchState.Loading
            try {
                val results = currentApi.search3(query)
                _searchResults.value = results
                _searchState.value =
                    if (results.isEmpty) SearchState.Empty else SearchState.Loaded(results)
            } catch (e: NavidromeApiException) {
                _searchState.value = SearchState.Error(e.searchUserMessage)
            } catch (e: Exception) {
                _searchState.value = SearchState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Clears the search query and resets state to [SearchState.Idle].
     */
    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchState.value = SearchState.Idle
        _searchResults.value = null
    }

    // -------------------------------------------------------------------------
    // Play bridge (baw.4.1 + E3 coordinator reuse)
    // -------------------------------------------------------------------------

    /**
     * Starts playback from a tapped search song result (baw.4.1).
     *
     * Enqueues ALL song results as a queue and starts from the tapped index,
     * so auto-advance works immediately.  Mirrors [NavidromeLibraryViewModel.playTrack].
     *
     * No-op when no API is configured or the track is not in the current results.
     */
    fun playSearchTrack(track: Track) {
        val currentApi = _api.value ?: return
        val tracks = _searchResults.value?.tracks ?: return
        val startIndex = tracks.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        musicPlaybackCoordinator.playAlbum(
            tracks = tracks,
            startIndex = startIndex,
            api = currentApi,
            albumTitle = "Search Results",
        )
    }
}

// -------------------------------------------------------------------------
// NavidromeApiException user-facing message (search scope)
// -------------------------------------------------------------------------

private val NavidromeApiException.searchUserMessage: String
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
