package com.adagiostream.android.ui.screens.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
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
 * button routes through [AudiobookPlaybackLauncher], bound to the playback
 * engine's AudiobookPlaybackCoordinator.playAudiobook (59p.1.5).
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

    /** The account behind [api] — the launcher plays through it. */
    private var absAccount: Account? = null

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
     * Resume/Play tap → starts playback via the coordinator (59p.1.5).
     * `resumeOverride = null`: the session's `/play` response carries the
     * server resume position. No-ops until the item and account are loaded.
     */
    fun play() {
        val account = absAccount ?: return
        val item = _item.value ?: return
        viewModelScope.launch {
            playbackLauncher.play(account, item.id, null)
        }
    }
}
