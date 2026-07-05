package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Bottom sheet for "Add to Playlist" (baw.4.3).
 *
 * Displays all server-side Navidrome playlists and lets the user pick one (or
 * create a new one).  On selection, [trackId] is appended to the chosen playlist
 * via [NavidromePlaylistViewModel.addTrackToPlaylist].
 *
 * Each invocation uses its own [NavidromePlaylistViewModel] scoped to the calling
 * screen's nav entry — it loads playlists fresh when the sheet opens.  This is
 * intentional: the sheet is lightweight and infrequently opened.
 *
 * [onDismiss] is called when the sheet is closed (either via selection or dismiss
 * gesture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    trackId: String,
    onDismiss: () -> Unit,
    viewModel: NavidromePlaylistViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlistsState.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    // Load playlists when the sheet opens.
    LaunchedEffect(Unit) {
        viewModel.loadPlaylists()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Add to Playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            // "New Playlist" option at the top.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreateDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "New Playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider()

            when (playlistsState) {
                NavidromePlaylistViewModel.LoadState.Loading,
                NavidromePlaylistViewModel.LoadState.Idle -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Loading playlists…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                NavidromePlaylistViewModel.LoadState.Empty -> {
                    Text(
                        text = "No playlists yet. Create one above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                is NavidromePlaylistViewModel.LoadState.Error -> {
                    Text(
                        text = "Couldn't load playlists. Tap New Playlist to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                NavidromePlaylistViewModel.LoadState.Loaded -> {
                    LazyColumn {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addTrackToPlaylist(
                                            playlistId = playlist.id,
                                            trackId = trackId,
                                        )
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (playlist.songCount > 0) {
                                        Text(
                                            text = "${playlist.songCount} song${if (playlist.songCount == 1) "" else "s"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }

            // Bottom padding for home-indicator area.
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showCreateDialog) {
        NewPlaylistDialog(
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                // After create, add the track — we can't know the new id immediately,
                // but baw.4.3 says to add-to-playlist by id: this creates and THEN
                // the user must select from the refreshed list.
                // For now just create; the user can re-open and pick it.
                showCreateDialog = false
                onDismiss()
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/** Inline dialog for creating a new playlist from AddToPlaylistSheet. */
@Composable
private fun NewPlaylistDialog(
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
