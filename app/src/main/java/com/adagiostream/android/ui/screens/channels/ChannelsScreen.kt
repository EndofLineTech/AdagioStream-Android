package com.adagiostream.android.ui.screens.channels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.Channel
import com.adagiostream.android.ui.components.ChannelListItem
import com.adagiostream.android.ui.components.GroupHeader
import com.adagiostream.android.ui.screens.epg.EPGBottomSheet
import com.adagiostream.android.ui.screens.favorites.FavoritesViewModel
import com.adagiostream.android.ui.screens.m3us.AddToPlaylistSheet
import com.adagiostream.android.service.playlist.CustomPlaylistManager

/**
 * Whether the pinned Favorites section atop the channel list should render
 * (beads_adagio-15x.1).
 *
 * Pure so the filtering rule — hidden while a search is active, hidden when
 * there are no favorites — is unit-testable without Compose.
 */
internal fun shouldShowPinnedFavorites(searchQuery: String, favoritesCount: Int): Boolean =
    searchQuery.isBlank() && favoritesCount > 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel = hiltViewModel(),
    favoritesViewModel: FavoritesViewModel = hiltViewModel(),
    onOpenGuide: () -> Unit = {},
    onManageFavorites: () -> Unit = {},
) {
    val groups by viewModel.filteredGroups.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val feedMetadata by viewModel.feedMetadata.collectAsStateWithLifecycle()
    val espnGames by viewModel.espnGames.collectAsStateWithLifecycle()
    val epgEntries by viewModel.epgEntries.collectAsStateWithLifecycle()
    val customPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var showEPGChannel by remember { mutableStateOf<Channel?>(null) }
    var addToPlaylistChannel by remember { mutableStateOf<Channel?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search channels") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearch("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )
            IconButton(onClick = onOpenGuide) {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Program guide")
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        error?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (groups.isEmpty() && !isLoading) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    item {
                        Text(
                            text = if (error != null) "Pull down to retry"
                                   else "No channels available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (shouldShowPinnedFavorites(searchQuery, favorites.size)) {
                        item(key = "pinned_favorites_header") {
                            PinnedFavoritesHeader(onManageClick = onManageFavorites)
                        }
                        items(favorites, key = { "pinned_favorite_${it.id}" }) { channel ->
                            val epgProgram = channel.epgChannelID?.let { epgId ->
                                epgEntries[epgId]?.firstOrNull { it.isCurrentlyAiring }?.title
                            }
                            ChannelListItem(
                                channel = channel,
                                onClick = { favoritesViewModel.playChannel(channel) },
                                onFavoriteToggle = { favoritesViewModel.toggleFavorite(channel) },
                                trackMetadata = feedMetadata[channel.id],
                                espnGame = espnGames[channel.id],
                                currentProgram = epgProgram,
                                onLongClick = { addToPlaylistChannel = channel },
                            )
                        }
                        item(key = "pinned_favorites_divider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    groups.forEach { group ->
                        val isExpanded = expandedGroups[group.name] ?: false

                        item(key = "header_${group.name}") {
                            GroupHeaderWithMenu(
                                name = group.name,
                                count = group.channels.size,
                                isExpanded = isExpanded,
                                isFavorite = viewModel.isGroupFavorite(group.name),
                                onToggle = {
                                    expandedGroups[group.name] = !isExpanded
                                },
                                onToggleFavorite = { viewModel.toggleGroupFavorite(group.name) },
                                onHide = { viewModel.hideGroup(group.name) },
                            )
                        }

                        if (isExpanded) {
                            items(
                                items = group.channels,
                                key = { "channel_${it.id}" },
                            ) { channel ->
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
                                    onLongClick = { addToPlaylistChannel = channel },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showEPGChannel?.let { epgChannel ->
        LaunchedEffect(epgChannel) {
            if (epgChannel.xtreamStreamId != null) {
                viewModel.loadEPGForChannel(epgChannel)
            }
        }
        val channelEpgId = epgChannel.epgChannelID ?: epgChannel.id
        EPGBottomSheet(
            entries = epgEntries[channelEpgId] ?: emptyList(),
            channelName = epgChannel.name,
            onDismiss = { showEPGChannel = null },
        )
    }

    addToPlaylistChannel?.let { channel ->
        AddToPlaylistSheet(
            channel = channel,
            playlistManager = viewModel.playlistManager,
            playlists = customPlaylists,
            onDismiss = { addToPlaylistChannel = null },
        )
    }
}

/**
 * Header for the pinned Favorites section atop the channel list (beads_adagio-15x.1).
 *
 * The Favorites tab was removed from the bottom nav; reordering favorites
 * still needs a home, so "Manage" reopens the existing [FavoritesScreen] —
 * reusing its drag-to-reorder UX rather than re-implementing it inline here.
 */
@Composable
private fun PinnedFavoritesHeader(onManageClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onManageClick) {
            Text("Manage")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeaderWithMenu(
    name: String,
    count: Int,
    isExpanded: Boolean,
    isFavorite: Boolean,
    onToggle: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        GroupHeader(
            name = name,
            count = count,
            isExpanded = isExpanded,
            onToggle = onToggle,
            modifier = Modifier.combinedClickable(
                onClick = onToggle,
                onLongClick = { showMenu = true },
            ),
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Unfavorite" else "Favorite") },
                onClick = {
                    showMenu = false
                    onToggleFavorite()
                },
            )
            DropdownMenuItem(
                text = { Text("Hide") },
                onClick = {
                    showMenu = false
                    onHide()
                },
            )
        }
    }
}
