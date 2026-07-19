package com.adagiostream.android.ui.screens.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.ui.screens.audiobooks.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Podcasts entry screen (beads_adagio-59p.2.1).
 *
 * Loads the server's libraries, keeps only podcast libraries (mediaType ==
 * "podcast"), and encodes the picker-vs-direct decision in [LibrariesState]
 * — same 0/1/N pattern as [com.adagiostream.android.ui.screens.audiobooks
 * .AudiobooksViewModel]:
 *
 *  - 0 podcast libraries → [LibrariesState.Empty]
 *  - 1 podcast library   → [LibrariesState.Single] — skip the picker
 *  - N podcast libraries → [LibrariesState.Picker]
 */
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val apiProvider: AudiobookshelfApiProvider,
) : ViewModel() {

    /** Picker-vs-direct-list decision states. */
    sealed class LibrariesState {
        data object Idle : LibrariesState()
        data object Loading : LibrariesState()
        data class Error(val message: String) : LibrariesState()

        /** The server has no podcast libraries (book-only servers included). */
        data object Empty : LibrariesState()

        /** Exactly one podcast library — skip the picker, show its shows. */
        data class Single(val library: AbsLibrary) : LibrariesState()

        /** Multiple podcast libraries — show the picker. */
        data class Picker(val libraries: List<AbsLibrary>) : LibrariesState()
    }

    private val _api = MutableStateFlow<AudiobookshelfApi?>(null)

    /** The active API, or null when no Audiobookshelf account is configured. */
    val api: StateFlow<AudiobookshelfApi?> = _api.asStateFlow()

    private val _librariesState = MutableStateFlow<LibrariesState>(LibrariesState.Idle)
    val librariesState: StateFlow<LibrariesState> = _librariesState.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                _api.value = apiProvider.apiFrom(accounts)
            }
        }
    }

    /**
     * Loads libraries and resolves the 0/1/N decision. No-ops without an API
     * (screen shows the no-account state) or while a load is in flight.
     */
    fun loadLibraries() {
        val api = _api.value ?: return
        if (_librariesState.value is LibrariesState.Loading) return

        viewModelScope.launch {
            _librariesState.value = LibrariesState.Loading
            try {
                val podcasts = api.getLibraries().filter { it.isPodcast }
                _librariesState.value = when {
                    podcasts.isEmpty() -> LibrariesState.Empty
                    podcasts.size == 1 -> LibrariesState.Single(podcasts.single())
                    else -> LibrariesState.Picker(podcasts)
                }
            } catch (e: AudiobookshelfApiException) {
                _librariesState.value = LibrariesState.Error(e.userMessage)
            } catch (e: Exception) {
                _librariesState.value = LibrariesState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Resets to [LibrariesState.Idle] and reloads. */
    fun retry() {
        _librariesState.value = LibrariesState.Idle
        loadLibraries()
    }
}
