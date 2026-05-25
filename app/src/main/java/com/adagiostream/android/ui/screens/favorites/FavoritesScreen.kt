package com.adagiostream.android.ui.screens.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.ui.components.ChannelListItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val feedMetadata by viewModel.feedMetadata.collectAsStateWithLifecycle()
    val espnGames by viewModel.espnGames.collectAsStateWithLifecycle()
    val epgEntries by viewModel.epgEntries.collectAsStateWithLifecycle()
    var localFavorites by remember(favorites) { mutableStateOf(favorites) }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localFavorites = localFavorites.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        viewModel.onReorder(from.index, to.index)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No favorites yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(localFavorites, key = { it.id }) { channel ->
                    ReorderableItem(reorderableLazyListState, key = channel.id) {
                        val epgProgram = channel.epgChannelID?.let { epgId ->
                            epgEntries[epgId]?.firstOrNull { it.isCurrentlyAiring }?.title
                        }
                        ChannelListItem(
                            channel = channel,
                            onClick = { viewModel.playChannel(channel) },
                            onFavoriteToggle = { viewModel.toggleFavorite(channel) },
                            trackMetadata = feedMetadata[channel.id],
                            espnGame = espnGames[channel.id],
                            currentProgram = epgProgram,
                            modifier = Modifier.animateItem(),
                            leadingContent = {
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
                                Spacer(modifier = Modifier.width(4.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}
