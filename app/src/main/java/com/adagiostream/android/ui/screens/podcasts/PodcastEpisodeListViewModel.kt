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
import com.adagiostream.android.service.audiobookshelf.PodcastProgressHydrator
import com.adagiostream.android.service.audiobookshelf.sortedEpisodes
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.ui.screens.audiobooks.AbsLoadState
import com.adagiostream.android.ui.screens.audiobooks.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The show's library item id (nav arg). */
    val itemId: String = savedStateHandle["itemId"] ?: ""

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

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
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

    /** Resets to [AbsLoadState.Idle] and reloads. */
    fun retry() {
        _episodesState.value = AbsLoadState.Idle
        load()
    }
}
