package com.adagiostream.android.ui.screens.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.AbsMediaProgress
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeEntry
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeOrder
import com.adagiostream.android.service.audiobookshelf.PodcastProgressHydrator
import com.adagiostream.android.service.audiobookshelf.PodcastShow
import com.adagiostream.android.service.audiobookshelf.partitionItemsInProgress
import com.adagiostream.android.service.audiobookshelf.sortedEpisodes
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.ui.screens.audiobooks.AbsLoadState
import com.adagiostream.android.ui.screens.audiobooks.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * ViewModel for one podcast library (beads_adagio-59p.2.1): the By Show grid,
 * the Recent Episodes flat list, and the Continue Listening episode shelf.
 *
 * Shows come from `GET /api/libraries/{id}/items` (minified, title-sorted
 * server-side). Recent Episodes needs each show's `media.episodes[]`, which
 * only the EXPANDED item detail carries — so it fetches `GET /api/items/{id}`
 * per show, bounded-parallel, cached per show for the screen's lifetime.
 *
 * Episode progress is hydrated on demand as rows become visible — see
 * [PodcastProgressHydrator].
 */
@HiltViewModel
class PodcastLibraryViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val apiProvider: AudiobookshelfApiProvider,
    private val persistenceService: PersistenceService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The library whose shows this screen shows (nav arg). */
    val libraryId: String = savedStateHandle["libraryId"] ?: ""

    /** The two browse modes, mirroring iOS's By Show | Recent Episodes menu. */
    enum class BrowseMode(val displayName: String) {
        BY_SHOW("By Show"),
        RECENT("Recent Episodes"),
    }

    private val _api = MutableStateFlow<AudiobookshelfApi?>(null)
    val api: StateFlow<AudiobookshelfApi?> = _api.asStateFlow()

    private val _browseMode = MutableStateFlow(BrowseMode.BY_SHOW)
    val browseMode: StateFlow<BrowseMode> = _browseMode.asStateFlow()

    private val _shows = MutableStateFlow<List<PodcastShow>>(emptyList())
    val shows: StateFlow<List<PodcastShow>> = _shows.asStateFlow()

    private val _showsState = MutableStateFlow<AbsLoadState>(AbsLoadState.Idle)
    val showsState: StateFlow<AbsLoadState> = _showsState.asStateFlow()

    private val _recentEpisodes = MutableStateFlow<List<PodcastEpisodeEntry>>(emptyList())

    /**
     * Every show's episodes flattened for the Recent Episodes mode: each show
     * internally sorted per the episode-order setting, shows folded together
     * in show-list order. Episodes are GROUPED BY SHOW, not interleaved
     * chronologically across shows — the same known iOS limitation
     * (`PodcastRecentEpisodes.aggregate`), accepted per the epic. True
     * cross-show ordering would flatten first then sort the whole set.
     */
    val recentEpisodes: StateFlow<List<PodcastEpisodeEntry>> = _recentEpisodes.asStateFlow()

    private val _recentState = MutableStateFlow<AbsLoadState>(AbsLoadState.Idle)
    val recentState: StateFlow<AbsLoadState> = _recentState.asStateFlow()

    private val _continueListening = MutableStateFlow<List<PodcastEpisodeEntry>>(emptyList())

    /** In-progress podcast episodes for the shelf, server order (most recent
     *  first) — the episode half of [partitionItemsInProgress]. */
    val continueListening: StateFlow<List<PodcastEpisodeEntry>> = _continueListening.asStateFlow()

    private val _episodeOrder = MutableStateFlow(PodcastEpisodeOrder.NEWEST_FIRST)

    /** The persisted episode-order setting, loaded per screen entry. */
    val episodeOrder: StateFlow<PodcastEpisodeOrder> = _episodeOrder.asStateFlow()

    private val hydrator = PodcastProgressHydrator(
        fetch = { itemId, episodeId ->
            val api = _api.value ?: throw IllegalStateException("No Audiobookshelf API")
            api.getEpisodeProgress(itemId, episodeId)
        },
    )

    /** Hydrated per-episode progress keyed by [PodcastProgressHydrator.key].
     *  Absent key = not yet hydrated. */
    val episodeProgress: StateFlow<Map<String, AbsMediaProgress?>> = hydrator.progress

    /** Expanded show details fetched for Recent Episodes, cached per show id
     *  so re-entering the mode doesn't refetch. */
    private val showEpisodesCache = mutableMapOf<String, List<AbsEpisode>>()

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                _api.value = apiProvider.apiFrom(accounts)
            }
        }
    }

    fun setBrowseMode(mode: BrowseMode) {
        _browseMode.value = mode
        if (mode == BrowseMode.RECENT && _recentState.value == AbsLoadState.Idle) {
            loadRecentEpisodes()
        }
    }

    /** Loads the show list and (best-effort) the Continue Listening shelf. */
    fun load() {
        val api = _api.value ?: return
        if (_showsState.value is AbsLoadState.Loading) return

        viewModelScope.launch {
            _episodeOrder.value = persistenceService.loadSettings().podcastEpisodeSortOrder
            _showsState.value = AbsLoadState.Loading
            try {
                val items = api.getLibraryItems(libraryId)
                val shows = items.mapNotNull { PodcastShow.from(it, libraryIdFallback = libraryId) }
                _shows.value = shows
                _showsState.value = if (shows.isEmpty()) AbsLoadState.Empty else AbsLoadState.Loaded
            } catch (e: AudiobookshelfApiException) {
                _showsState.value = AbsLoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _showsState.value = AbsLoadState.Error(e.message ?: "Unknown error")
            }
        }
        // The shelf is SERVER-WIDE (items-in-progress spans all libraries),
        // matching the audiobooks shelf. Best-effort: a failure leaves it
        // empty and never blocks the show grid.
        viewModelScope.launch {
            _continueListening.value = try {
                partitionItemsInProgress(api.getItemsInProgress()).episodes
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Loads the Recent Episodes list: one expanded item fetch per show,
     * bounded to [DETAIL_FETCH_CONCURRENCY] in flight (ceiling: a library of
     * N shows costs N requests total, first entry only — results are cached).
     * Requires the show list; called lazily on first switch to
     * [BrowseMode.RECENT].
     */
    fun loadRecentEpisodes() {
        val api = _api.value ?: return
        if (_recentState.value is AbsLoadState.Loading) return

        viewModelScope.launch {
            _recentState.value = AbsLoadState.Loading
            try {
                // Shows may still be loading on a fast mode switch — reuse
                // the already-fetched list or fetch it here.
                val shows = _shows.value.ifEmpty {
                    api.getLibraryItems(libraryId)
                        .mapNotNull { PodcastShow.from(it, libraryIdFallback = libraryId) }
                }
                val order = _episodeOrder.value
                val semaphore = Semaphore(DETAIL_FETCH_CONCURRENCY)
                val perShow = coroutineScope {
                    shows.map { show ->
                        async {
                            val episodes = showEpisodesCache[show.id] ?: semaphore.withPermit {
                                (api.getItem(show.id).media?.episodes ?: emptyList())
                                    .also { showEpisodesCache[show.id] = it }
                            }
                            sortedEpisodes(episodes, order).map { episode ->
                                PodcastEpisodeEntry(
                                    episode = episode,
                                    showLibraryItemId = show.id,
                                    showTitle = show.title,
                                )
                            }
                        }
                    }.awaitAll()
                }
                val flattened = perShow.flatten()
                _recentEpisodes.value = flattened
                _recentState.value =
                    if (flattened.isEmpty()) AbsLoadState.Empty else AbsLoadState.Loaded
            } catch (e: AudiobookshelfApiException) {
                _recentState.value = AbsLoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _recentState.value = AbsLoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Row-visibility hook: LazyColumn composes only visible rows, so each
     * row's first composition requests its own hydration. The hydrator
     * dedupes and bounds concurrency — scrolling back over hydrated rows
     * costs nothing.
     */
    fun onEpisodeVisible(showLibraryItemId: String, episodeId: String) {
        viewModelScope.launch {
            hydrator.hydrate(listOf(showLibraryItemId to episodeId))
        }
    }

    /** Resets the show list to Idle and reloads (shelf included). */
    fun retry() {
        _showsState.value = AbsLoadState.Idle
        load()
    }

    /** Resets Recent Episodes to Idle and reloads. */
    fun retryRecent() {
        _recentState.value = AbsLoadState.Idle
        loadRecentEpisodes()
    }

    companion object {
        /** Max concurrent expanded-item fetches for Recent Episodes. */
        private const val DETAIL_FETCH_CONCURRENCY = 4
    }
}
