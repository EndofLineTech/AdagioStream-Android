package com.adagiostream.android.ui.screens.groups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Search
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManagementScreen(
    onBack: () -> Unit,
    viewModel: GroupManagementViewModel = hiltViewModel(),
) {
    val groupItems by viewModel.groupItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Groups") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Tap (+) to add a group to your favorites. Favorite groups appear first in your channel list and Android Auto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text("Search groups...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.setAllEnabled(true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enable All")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.setAllEnabled(false) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Disable All")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
        }

        val favoriteGroups = groupItems.filter { it.isFavorite }
        val otherGroups = groupItems.filter { !it.isFavorite }
        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            viewModel.onReorder(from.index, to.index)
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (favoriteGroups.isNotEmpty()) {
                item(key = "favorites_header") {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                items(favoriteGroups, key = { "fav_${it.name}" }) { group ->
                    ReorderableItem(reorderableState, key = "fav_${group.name}") {
                        GroupRow(
                            group = group,
                            onToggleEnabled = { viewModel.toggleEnabled(group.name) },
                            onToggleFavorite = { viewModel.toggleFavorite(group.name) },
                            dragHandle = {
                                IconButton(
                                    modifier = Modifier.draggableHandle(),
                                    onClick = {},
                                ) {
                                    Icon(Icons.Default.DragHandle, contentDescription = "Reorder")
                                }
                            },
                        )
                    }
                }

                item(key = "favorites_footer") {
                    Text(
                        text = "Favorite groups appear first in your channel list and Android Auto. Drag to reorder them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            item(key = "other_groups_header") {
                Text(
                    text = if (favoriteGroups.isNotEmpty()) "Other Groups" else "Groups",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            items(otherGroups, key = { "grp_${it.name}" }) { group ->
                GroupRow(
                    group = group,
                    onToggleEnabled = { viewModel.toggleEnabled(group.name) },
                    onToggleFavorite = { viewModel.toggleFavorite(group.name) },
                )
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: GroupItem,
    onToggleEnabled: () -> Unit,
    onToggleFavorite: () -> Unit,
    dragHandle: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            dragHandle()
        }

        Switch(
            checked = group.isEnabled,
            onCheckedChange = { onToggleEnabled() },
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${group.channelCount} channels",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onToggleFavorite) {
            if (group.isFavorite) {
                Icon(
                    imageVector = Icons.Default.RemoveCircle,
                    contentDescription = "Remove from favorites",
                    tint = MaterialTheme.colorScheme.error,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AddCircle,
                    contentDescription = "Add to favorites",
                    tint = Color(0xFF4CAF50),
                )
            }
        }
    }
}
