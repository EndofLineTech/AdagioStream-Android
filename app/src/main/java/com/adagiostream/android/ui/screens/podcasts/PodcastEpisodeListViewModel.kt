package com.adagiostream.android.ui.screens.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.AbsMediaProgress
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeOrder
import com.adagiostream.android.service.audiobookshelf.PodcastPlaybackContext
import com.adagiostream.android.service.audiobookshelf.PodcastProgressHydrator
import com.adagiostream.android.service.audiobookshelf.sortedEpisodes
import com.adagiostream.android.service.download.AudiobookDownloadActions
import com.adagiostream.android.service.download.AudiobookDownloadManager
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.PodcastPlaybackLauncher
import com.adagiostream.android.ui.screens.audiobooks.AbsLoadState
import com.adagiostream.android.ui.screens.audiobooks.userMessage
import com.adagiostream.android.ui.screens.music.DownloadUiState
import com.adagiostream.android.ui.screens.music.toUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for one podcast show's episode list (beads_adagio-59p.2.1).
 *
 * One expanded item fetch (`GET /api/items/{id}`) delivers the show header
 * and `media.episodes[]`; the list is sorted per the persisted episode-order
 * setting via the shared
 * [sortedEpisodes][com.adagiostream.android.service.audiobookshelf.sortedEpisodes]
 * seam. Per-episode progress is hydrated on demand as rows become visible
 * ([PodcastProgressHydrator]).
 */
@HiltViewModel
class PodcastEpisodeListViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val apiProvider: AudiobookshelfApiProvider,
    private val persistenceService: PersistenceService,
    private val playbackLauncher: PodcastPlaybackLauncher,
    private val downloadActions: AudiobookDownloadActions,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The show's library item id (nav arg). */
    val itemId: String = savedStateHandle["itemId"] ?: ""

    /**
     * episodeId → download button state (bead .2.3), kept live from the shared
     * audiobook_downloads table. Filters the table to THIS show's episode
     * records by the composite id prefix, then strips it back to episodeId.
     */
    val downloadStates: StateFlow<Map<String, DownloadUiState>> =
        downloadActions.observeAll()
            .map { rows ->
                val prefix = AudiobookDownloadManager.episodeRecordId(itemId, "")
                rows.filter { it.id.startsWith(prefix) }
                    .associate { it.id.removePrefix(prefix) to it.status.toUiState() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _api = MutableStateFlow<AudiobookshelfApi?>(null)
    val api: StateFlow<AudiobookshelfApi?> = _api.asStateFlow()

    private val _showTitle = MutableStateFlow<String?>(null)
    val showTitle: StateFlow<String?> = _showTitle.asStateFlow()

    private val _episodes = MutableStateFlow<List<AbsEpisode>>(emptyList())

    /** The show's episodes, already sorted per the episode-order setting. */
    val episodes: StateFlow<List<AbsEpisode>> = _episodes.asStateFlow()

    private val _episodesState = MutableStateFlow<AbsLoadState>(AbsLoadState.Idle)
    val episodesState: StateFlow<AbsLoadState> = _episodesState.asStateFlow()

    private val hydrator = PodcastProgressHydrator(
        fetch = { libraryItemId, episodeId ->
            val api = _api.value ?: throw IllegalStateException("No Audiobookshelf API")
            api.getEpisodeProgress(libraryItemId, episodeId)
        },
    )

    /** Hydrated per-episode progress keyed by [PodcastProgressHydrator.key].
     *  Absent key = not yet hydrated. */
    val episodeProgress: StateFlow<Map<String, AbsMediaProgress?>> = hydrator.progress

    /** The account behind [api] — the launcher plays through it. */
    private var absAccount: Account? = null

    /** The order [episodes] was sorted with — carried into the playback
     *  context so display order and auto-play direction can't disagree. */
    private var loadedOrder: PodcastEpisodeOrder = PodcastEpisodeOrder.NEWEST_FIRST

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                absAccount = accounts.firstOrNull {
                    it.isEnabled && it.type is AccountType.Audiobookshelf
                }
                _api.value = apiProvider.apiFrom(accounts)
            }
        }
    }

    /** Loads the show detail and sorts its episodes per the setting. */
    fun load() {
        val api = _api.value ?: return
        if (_episodesState.value is AbsLoadState.Loading) return

        viewModelScope.launch {
            _episodesState.value = AbsLoadState.Loading
            try {
                val order = persistenceService.loadSettings().podcastEpisodeSortOrder
                loadedOrder = order
                val item = api.getItem(itemId)
                _showTitle.value = item.media?.metadata?.title
                val sorted = sortedEpisodes(item.media?.episodes ?: emptyList(), order)
                _episodes.value = sorted
                _episodesState.value =
                    if (sorted.isEmpty()) AbsLoadState.Empty else AbsLoadState.Loaded
            } catch (e: AudiobookshelfApiException) {
                _episodesState.value = AbsLoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _episodesState.value = AbsLoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Row-visibility hydration hook — see
     * [PodcastLibraryViewModel.onEpisodeVisible]. Deduped + bounded by the
     * hydrator, so recomposition and scroll-back are free.
     */
    fun onEpisodeVisible(episodeId: String) {
        viewModelScope.launch {
            hydrator.hydrate(listOf(itemId to episodeId))
        }
    }

    /**
     * Plays an episode (beads_adagio-59p.2.2): builds the auto-play context
     * from the already-loaded episode list + order and hands it to the
     * playback engine via [PodcastPlaybackLauncher].
     */
    fun playEpisode(episodeId: String) {
        val account = absAccount ?: return
        val context = PodcastPlaybackContext(
            libraryItemId = itemId,
            showTitle = _showTitle.value,
            episodes = _episodes.value,
            order = loadedOrder,
        )
        viewModelScope.launch {
            playbackLauncher.play(account, itemId, episodeId, context)
        }
    }

    /**
     * Download-button tap (bead .2.3): download when absent/failed, cancel
     * in-flight, delete when complete — mirrors the music [TrackDownloadButton]
     * state machine, keyed by the composite (show, episode) record.
     */
    fun onDownloadTap(episodeId: String, state: DownloadUiState) {
        val account = absAccount ?: return
        val episode = _episodes.value.firstOrNull { it.id == episodeId }
        viewModelScope.launch {
            when (state) {
                DownloadUiState.NOT_DOWNLOADED, DownloadUiState.FAILED ->
                    downloadActions.downloadEpisode(
                        account, itemId, episodeId,
                        title = episode?.title,
                        author = _showTitle.value,
                    )
                DownloadUiState.QUEUED, DownloadUiState.DOWNLOADING, DownloadUiState.COMPLETED ->
                    downloadActions.deleteEpisode(itemId, episodeId)
            }
        }
    }

    /** Resets to [AbsLoadState.Idle] and reloads. */
    fun retry() {
        _episodesState.value = AbsLoadState.Idle
        load()
    }
}
