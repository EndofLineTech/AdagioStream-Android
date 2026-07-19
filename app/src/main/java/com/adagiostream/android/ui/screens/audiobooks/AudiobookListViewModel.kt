package com.adagiostream.android.ui.screens.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for one library's book list + the Continue Listening shelf
 * (beads_adagio-59p.1.4).
 *
 * Books come from `GET /api/libraries/{id}/items` sorted server-side by
 * title. The shelf comes from `GET /api/me/items-in-progress`, filtered by
 * [continueListeningBooks] (books only, started, not finished) — best-effort
 * like the iOS port: a shelf failure leaves it empty and never blocks the
 * book list.
 */
@HiltViewModel
class AudiobookListViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val apiProvider: AudiobookshelfApiProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The library whose books this screen shows (nav arg). */
    val libraryId: String = savedStateHandle["libraryId"] ?: ""

    private val _api = MutableStateFlow<AudiobookshelfApi?>(null)
    val api: StateFlow<AudiobookshelfApi?> = _api.asStateFlow()

    private val _books = MutableStateFlow<List<AbsLibraryItem>>(emptyList())
    val books: StateFlow<List<AbsLibraryItem>> = _books.asStateFlow()

    private val _booksState = MutableStateFlow<AbsLoadState>(AbsLoadState.Idle)
    val booksState: StateFlow<AbsLoadState> = _booksState.asStateFlow()

    private val _continueListening = MutableStateFlow<List<AbsLibraryItem>>(emptyList())

    /** Started-and-unfinished books, server order (most recent first). */
    val continueListening: StateFlow<List<AbsLibraryItem>> = _continueListening.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                _api.value = apiProvider.apiFrom(accounts)
            }
        }
    }

    /** Loads the book list and (best-effort) the Continue Listening shelf. */
    fun load() {
        val api = _api.value ?: return
        if (_booksState.value is AbsLoadState.Loading) return

        viewModelScope.launch {
            _booksState.value = AbsLoadState.Loading
            try {
                val items = api.getLibraryItems(libraryId)
                _books.value = items
                _booksState.value = if (items.isEmpty()) AbsLoadState.Empty else AbsLoadState.Loaded
            } catch (e: AudiobookshelfApiException) {
                _booksState.value = AbsLoadState.Error(e.userMessage)
            } catch (e: Exception) {
                _booksState.value = AbsLoadState.Error(e.message ?: "Unknown error")
            }
        }
        // The shelf is intentionally SERVER-WIDE (items-in-progress spans all
        // libraries), matching iOS's merged-library Continue Listening shelf.
        viewModelScope.launch {
            _continueListening.value = try {
                continueListeningBooks(api.getItemsInProgress())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList() // Best-effort: shelf failure never blocks the list.
            }
        }
    }

    /** Resets to [AbsLoadState.Idle] and reloads. */
    fun retry() {
        _booksState.value = AbsLoadState.Idle
        load()
    }
}
