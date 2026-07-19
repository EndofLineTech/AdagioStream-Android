package com.adagiostream.android.ui.screens.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.player.AudiobookPlaybackLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for one audiobook's detail screen (beads_adagio-59p.1.4).
 *
 * Loads the expanded item (`GET /api/items/{id}?expanded=1&include=progress`)
 * so the header shows fresh progress and the chapter count. The Resume/Play
 * button routes through [AudiobookPlaybackLauncher] — a no-op until the
 * playback branch (59p.1.5) binds the real implementation.
 */
@HiltViewModel
class AudiobookDetailViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val apiProvider: AudiobookshelfApiProvider,
    private val playbackLauncher: AudiobookPlaybackLauncher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The library item id (nav arg). */
    val itemId: String = savedStateHandle["itemId"] ?: ""

    private val _api = MutableStateFlow<AudiobookshelfApi?>(null)
    val api: StateFlow<AudiobookshelfApi?> = _api.asStateFlow()

    private val _item = MutableStateFlow<AbsLibraryItem?>(null)
    val item: StateFlow<AbsLibraryItem?> = _item.asStateFlow()

    private val _itemState = MutableStateFlow<AbsLoadState>(AbsLoadState.Idle)
    val itemState: StateFlow<AbsLoadState> = _itemState.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                _api.value = apiProvider.apiFrom(accounts)
            }
        }
    }

    /** Loads the expanded item with fresh progress. */
    fun load() {
        val api = _api.value ?: return
        if (_itemState.value is AbsLoadState.Loading) return

        viewModelScope.launch {
            _itemState.value = AbsLoadState.Loading
            try {
                _item.value = api.getItem(itemId)
                _itemState.value = AbsLoadState.Loaded
            } catch (e: AudiobookshelfApiException) {
                _itemState.value = AbsLoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _itemState.value = AbsLoadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Resets to [AbsLoadState.Idle] and reloads. */
    fun retry() {
        _itemState.value = AbsLoadState.Idle
        load()
    }

    /**
     * Resume/Play tap → the 59p.1.5 integration point. No-ops until an item
     * and API are available.
     */
    fun play() {
        val api = _api.value ?: return
        val item = _item.value ?: return
        playbackLauncher.play(item, api, null)
    }
}
