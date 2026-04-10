package com.adagiostream.android.ui.screens.m3us

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.CustomPlaylistGroup
import com.adagiostream.android.service.playlist.CustomPlaylistManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    channel: Channel,
    playlistManager: CustomPlaylistManager,
    playlists: List<CustomPlaylist>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPlaylist by remember { mutableStateOf<CustomPlaylist?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Add \"${channel.name}\" to playlist",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            val playlist = selectedPlaylist
            if (playlist == null) {
                // Step 1: Pick a playlist
                Text("Select playlist:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    items(playlists) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPlaylist = p }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(p.name, modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCreatePlaylist = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("New Playlist", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                // Step 2: Pick a group
                Text("Select group in \"${playlist.name}\":", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { selectedPlaylist = null }) {
                    Text("< Back to playlists")
                }
                LazyColumn {
                    items(playlist.groups) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playlistManager.addChannel(channel, group.id, playlist.id)
                                    onDismiss()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(group.name, modifier = Modifier.weight(1f))
                            Text(
                                "${group.entries.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCreateGroup = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("New Group", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showCreatePlaylist) {
        TextInputDialog(
            title = "New Playlist",
            label = "Playlist name",
            confirmText = "Create",
            onConfirm = { name ->
                val newPlaylist = playlistManager.createPlaylist(name)
                selectedPlaylist = newPlaylist
                showCreatePlaylist = false
            },
            onDismiss = { showCreatePlaylist = false },
        )
    }

    if (showCreateGroup) {
        val playlist = selectedPlaylist
        if (playlist != null) {
            TextInputDialog(
                title = "New Group",
                label = "Group name",
                confirmText = "Create",
                onConfirm = { name ->
                    val newGroup = playlistManager.addGroup(name, playlist.id)
                    if (newGroup != null) {
                        playlistManager.addChannel(channel, newGroup.id, playlist.id)
                        onDismiss()
                    }
                    showCreateGroup = false
                },
                onDismiss = { showCreateGroup = false },
            )
        }
    }
}
