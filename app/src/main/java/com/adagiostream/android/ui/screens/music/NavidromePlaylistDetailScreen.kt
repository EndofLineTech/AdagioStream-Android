package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.adagiostream.android.service.navidrome.NavidromePlaylist
import com.adagiostream.android.service.navidrome.Track

/**
 * Navidrome playlist detail screen (baw.4.2 / baw.4.3).
 *
 * Shows tracks for a single Navidrome server-side playlist.  Provides:
 *  - "Play All" button to start playback from the first track.
 *  - Tap-to-play on individual tracks (starts queue from tapped index).
 *  - Rename / Delete actions in the top-bar overflow menu.
 *  - "Add to Playlist" (⋮) on each track row ([AddToPlaylistSheet]).
 *
 * [playlistId] is resolved from the nav argument.  A minimal [NavidromePlaylist]
 * stub is constructed for the initial [NavidromePlaylistViewModel.loadPlaylistDetail]
 * call; the ViewModel overwrites it with the real data from `getPlaylist`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavidromePlaylistDetailScreen(
    viewModel: NavidromePlaylistViewModel = hiltViewModel(),
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    playlistId: String,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsStateWithLifecycle()
    val detailState by viewModel.playlistDetailState.collectAsStateWithLifecycle()
    val tracks by viewModel.playlistTracks.collectAsStateWithLifecycle()
    val downloadStates by downloadsViewModel.downloadStates.collectAsStateWithLifecycle()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var addToPlaylistTrackId by remember { mutableStateOf<String?>(null) }

    // Load playlist detail on first appearance.
    LaunchedEffect(api, playlistId) {
        if (api == null) return@LaunchedEffect
        val playlist = selectedPlaylist?.takeIf { it.id == playlistId }
            ?: NavidromePlaylist(id = playlistId, name = "", songCount = 0)
        if (detailState == NavidromePlaylistViewModel.LoadState.Idle ||
            selectedPlaylist?.id != playlistId
        ) {
            viewModel.loadPlaylistDetail(playlist)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = selectedPlaylist?.name ?: "Playlist",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                // "Play All" shortcut.
                if (tracks.isNotEmpty()) {
                    IconButton(onClick = { viewModel.playAllPlaylistTracks() }) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play all",
                        )
                    }
                    // "Download All" — bulk enqueue the whole playlist (baw.6.2).
                    IconButton(onClick = { downloadsViewModel.downloadAll(tracks) }) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download all",
                        )
                    }
                }
                // Overflow: rename / delete.
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showOverflowMenu = false
                                showRenameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                showDeleteDialog = true
                            },
                        )
                    }
                }
            },
        )

        when (detailState) {
            NavidromePlaylistViewModel.LoadState.Idle,
            NavidromePlaylistViewModel.LoadState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading playlist…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromePlaylistViewModel.LoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "This playlist has no songs yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromePlaylistViewModel.LoadState.Error -> {
                val message =
                    (detailState as NavidromePlaylistViewModel.LoadState.Error).message
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = "Couldn't load playlist",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retryPlaylistDetail() }) { Text("Retry") }
                    }
                }
            }

            NavidromePlaylistViewModel.LoadState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Composite key (index + id): playlists can contain the same song
                    // more than once, so the id alone is not a stable/unique key.
                    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        PlaylistTrackRow(
                            track = track,
                            downloadState = downloadStates[track.id] ?: DownloadUiState.NOT_DOWNLOADED,
                            onClick = { viewModel.playPlaylistTrack(track) },
                            onAddToPlaylist = { addToPlaylistTrackId = track.id },
                            // Removal is index-based (baw.9.2) — always the row's
                            // position in THIS list, never the track id, so
                            // duplicate tracks remove the exact tapped occurrence.
                            onRemoveFromPlaylist = {
                                selectedPlaylist?.let { viewModel.removeTrackAt(it, index) }
                            },
                            onDownloadTap = { state -> downloadsViewModel.onButtonTap(track, state) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    // Rename dialog.
    if (showRenameDialog && selectedPlaylist != null) {
        val playlist = selectedPlaylist!!
        var newName by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePlaylist(playlist, newName.trim())
                        showRenameDialog = false
                    },
                    enabled = newName.isNotBlank() && newName != playlist.name,
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Delete confirmation dialog.
    if (showDeleteDialog && selectedPlaylist != null) {
        val playlist = selectedPlaylist!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Playlist?") },
            text = {
                Text(
                    "\"${playlist.name}\" will be permanently deleted from Navidrome.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(playlist)
                    showDeleteDialog = false
                    onBack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    // "Add to Playlist" bottom sheet.
    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistSheet(
            trackId = trackId,
            onDismiss = { addToPlaylistTrackId = null },
        )
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    downloadState: DownloadUiState,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onDownloadTap: (DownloadUiState) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
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

        // Download button — 5 states (baw.6.2)
        TrackDownloadButton(state = downloadState, onTap = { onDownloadTap(downloadState) })

        if (track.duration != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatPlaylistDuration(track.duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Overflow — "Add to Playlist" (baw.4.3) + "Remove from Playlist" (baw.9.2).
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Add to Playlist") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove from Playlist", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onRemoveFromPlaylist()
                    },
                )
            }
        }
    }
}

private fun formatPlaylistDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
