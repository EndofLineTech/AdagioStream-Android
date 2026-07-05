package com.adagiostream.android.ui.screens.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.service.navidrome.Track
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * A queue row with an identity-stable drag key (baw.16). Duplicate tracks in a
 * queue share a track id, so the uid disambiguates occurrences — and unlike an
 * index-composite key it MOVES WITH the entry during a drag, which is what
 * `sh.calvin.reorderable` needs to keep tracking the dragged item (same
 * stable-key requirement as LovedTracksScreen's content-based keys).
 */
private data class QueueEntry(val uid: String, val track: Track)

private fun List<Track>.toEntries(): List<QueueEntry> {
    val seen = mutableMapOf<String, Int>()
    // ponytail: occurrence-based uid ("id#n") — two identical duplicate rows may
    // swap uids when one is dragged past the other, but they're visually
    // indistinguishable so nothing glitches.
    return map { t ->
        val n = seen.merge(t.id, 1, Int::plus)!!
        QueueEntry(uid = "${t.id}#$n", track = t)
    }
}

/**
 * "Up Next" — reorderable library queue, reachable from [NowPlayingSheet] (baw.9.3).
 *
 * Only ever shown while a library (on-demand) track is playing — never for radio,
 * which has no queue concept. Tap a row to jump straight to it
 * ([NowPlayingViewModel.playQueueIndex]); drag the handle to reorder
 * ([NowPlayingViewModel.moveQueueItem]).
 *
 * The sheet always displays the CANONICAL queue. While shuffle is on the play
 * order diverges from it, so drag handles are hidden (Spotify-style) and
 * reorder is disabled — see [MusicPlaybackCoordinator.moveQueueItem] (baw.16).
 * Tap-to-jump remains available in both modes.
 *
 * Reuses the same `sh.calvin.reorderable` drag-to-reorder pattern already used
 * for Favorites/Loved lists elsewhere in the app (see LovedTracksScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.libraryQueue.collectAsStateWithLifecycle()
    val currentIndex by viewModel.libraryQueueIndex.collectAsStateWithLifecycle()
    val isShuffleEnabled by viewModel.isShuffleEnabled.collectAsStateWithLifecycle()
    var localQueue by remember(queue) { mutableStateOf(queue.toEntries()) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localQueue = localQueue.toMutableList().apply { add(to.index, removeAt(from.index)) }
        viewModel.moveQueueItem(from.index, to.index)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "Up Next",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        ) {
            itemsIndexed(localQueue, key = { _, entry -> entry.uid }) { index, entry ->
                ReorderableItem(reorderableState, key = entry.uid) {
                    UpNextRow(
                        track = entry.track,
                        isPlaying = index == currentIndex,
                        onClick = { viewModel.playQueueIndex(index) },
                        // Reorder is canonical-domain only; hidden under shuffle (baw.16).
                        dragHandle = if (isShuffleEnabled) null else {
                            {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun UpNextRow(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    dragHandle: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            dragHandle()
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (isPlaying) {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = "Now playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist != null) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
