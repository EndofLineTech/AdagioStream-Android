package com.adagiostream.android.ui.screens.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Audiobooks entry screen (beads_adagio-59p.1.4).
 *
 * Loads the server's libraries, keeps only book libraries (mediaType ==
 * "book" — podcast libraries are a later bead), and encodes the
 * picker-vs-direct decision in [LibrariesState]:
 *
 *  - 0 book libraries → [LibrariesState.Empty]
 *  - 1 book library   → [LibrariesState.Single] — the screen goes straight to
 *    the book list, no picker
 *  - N book libraries → [LibrariesState.Picker]
 *
 * The API is resolved by watching [AccountRepository.accounts] for the first
 * enabled Audiobookshelf account, exactly like NavidromeLibraryViewModel
 * resolves its Subsonic API.
 */
@HiltViewModel
class AudiobooksViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val apiProvider: AudiobookshelfApiProvider,
) : ViewModel() {

    /** Picker-vs-direct-list decision states. */
    sealed class LibrariesState {
        data object Idle : LibrariesState()
        data object Loading : LibrariesState()
        data class Error(val message: String) : LibrariesState()

        /** The server has no book libraries (podcast-only servers included). */
        data object Empty : LibrariesState()

        /** Exactly one book library — skip the picker, show its books. */
        data class Single(val library: AbsLibrary) : LibrariesState()

        /** Multiple book libraries — show the picker. */
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
                val books = api.getLibraries().filter { it.isBook }
                _librariesState.value = when {
                    books.isEmpty() -> LibrariesState.Empty
                    books.size == 1 -> LibrariesState.Single(books.single())
                    else -> LibrariesState.Picker(books)
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
