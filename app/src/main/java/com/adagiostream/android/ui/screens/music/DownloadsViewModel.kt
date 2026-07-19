package com.adagiostream.android.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.download.AudiobookDownloadManager
import com.adagiostream.android.service.download.DownloadFileStore
import com.adagiostream.android.service.download.DownloadManager
import com.adagiostream.android.service.download.StorageAccounting
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.navidrome.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-track download state for the 5-state download button (baw.6.2):
 * not-downloaded / queued / downloading / completed / failed (paused folds into
 * failed for the button, both offering "retry").
 */
enum class DownloadUiState { NOT_DOWNLOADED, QUEUED, DOWNLOADING, COMPLETED, FAILED }

/** A downloaded track shown on the storage-management screen (baw.6.2). */
data class DownloadedItem(
    val trackId: String,
    val title: String,
    val subtitle: String,
    val sizeBytes: Long,
)

/** A downloaded audiobook shown on the storage-management screen (beads_adagio-59p.1.6). */
data class DownloadedAudiobookItem(
    val libraryItemId: String,
    val title: String,
    val author: String,
    val sizeBytes: Long,
    val status: String,
)

/**
 * Drives the download UI (baw.6.2): per-track button state + actions, bulk
 * "Download All", and the storage-management listing.
 *
 * Obtained at screen level via `hiltViewModel()` and shared down to track rows.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    private val repository: MusicLibraryRepository,
    private val fileStore: DownloadFileStore,
    private val audiobookDownloads: AudiobookDownloadManager,
) : ViewModel() {

    /** trackId → button state, kept live from the `downloads` table. */
    val downloadStates: StateFlow<Map<String, DownloadUiState>> =
        repository.observeAllDownloads()
            .map { rows -> rows.associate { it.id to it.status.toUiState() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Downloaded-content listing (title + size) for the storage screen. */
    val storageItems: StateFlow<List<DownloadedItem>> =
        repository.observeCompletedDownloads()
            .map { rows ->
                val tracks = repository.downloadedTracks().associateBy { it.id }
                rows.map { row ->
                    val track = tracks[row.id]
                    DownloadedItem(
                        trackId = row.id,
                        title = track?.title ?: row.id,
                        subtitle = track?.artist ?: "",
                        sizeBytes = row.localPath?.let { fileStore.sizeOf(it) } ?: 0L,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Downloaded audiobooks (title + size) for the storage screen (beads_adagio-59p.1.6). */
    val audiobookItems: StateFlow<List<DownloadedAudiobookItem>> =
        audiobookDownloads.observeAll()
            .map { rows ->
                rows.map { row ->
                    DownloadedAudiobookItem(
                        libraryItemId = row.id,
                        title = row.title,
                        author = row.author ?: "",
                        sizeBytes = audiobookDownloads.sizeOf(row),
                        status = row.status,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Total on-disk size of all downloads (File.length sums, never a DB counter). */
    val totalBytes: StateFlow<Long> =
        combine(storageItems, audiobookItems) { music, books ->
            StorageAccounting.totalBytes(music.map { it.sizeBytes } + books.map { it.sizeBytes })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun formatSize(bytes: Long): String = StorageAccounting.formatSize(bytes)

    // ---- actions ---------------------------------------------------------

    /** Tap action dispatcher for the per-track button, given its current state. */
    fun onButtonTap(track: Track, state: DownloadUiState) {
        when (state) {
            DownloadUiState.NOT_DOWNLOADED -> download(track)
            DownloadUiState.QUEUED, DownloadUiState.DOWNLOADING -> cancel(track.id)
            DownloadUiState.COMPLETED -> delete(track.id)
            DownloadUiState.FAILED -> retry(track)
        }
    }

    fun download(track: Track) = viewModelScope.launch { downloadManager.enqueue(track) }

    fun downloadAll(tracks: List<Track>) = viewModelScope.launch { downloadManager.enqueueAll(tracks) }

    fun cancel(trackId: String) = viewModelScope.launch { downloadManager.cancel(trackId) }

    fun delete(trackId: String) = viewModelScope.launch { downloadManager.delete(trackId) }

    fun retry(track: Track) = viewModelScope.launch { downloadManager.retry(track) }

    fun deleteAll() = viewModelScope.launch { downloadManager.deleteAll() }

    /** Deletes one downloaded audiobook (files + manifest; cancels in-flight work). */
    fun deleteAudiobook(libraryItemId: String) =
        viewModelScope.launch { audiobookDownloads.delete(libraryItemId) }
}

/** Maps a stored status string to the button's UI state. */
internal fun String.toUiState(): DownloadUiState = when (this) {
    DownloadStatus.QUEUED -> DownloadUiState.QUEUED
    DownloadStatus.DOWNLOADING -> DownloadUiState.DOWNLOADING
    DownloadStatus.COMPLETED -> DownloadUiState.COMPLETED
    DownloadStatus.FAILED, DownloadStatus.PAUSED -> DownloadUiState.FAILED
    else -> DownloadUiState.NOT_DOWNLOADED
}
