package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

/**
 * Navidrome playlist list screen (baw.4.2).
 *
 * Lists all server-side Navidrome playlists, sorted alphabetically.
 * Provides a FAB to create a new playlist and swipe/long-press actions
 * for rename and delete (baw.4.3).
 *
 * NAMING: uses [NavidromePlaylist] — entirely separate from the IPTV M3U
 * [CustomPlaylist] system (PlaylistDetail route).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavidromePlaylistListScreen(
    viewModel: NavidromePlaylistViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlistsState.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    // Playlist pending rename (null = no dialog shown).
    var renameTarget by remember { mutableStateOf<NavidromePlaylist?>(null) }
    // Playlist pending delete confirmation (null = no dialog shown).
    var deleteTarget by remember { mutableStateOf<NavidromePlaylist?>(null) }

    // Load playlists on first appearance when an API is available.
    LaunchedEffect(api) {
        if (api != null && playlistsState == NavidromePlaylistViewModel.LoadState.Idle) {
            viewModel.loadPlaylists()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (api != null) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New playlist")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (playlistsState) {
                NavidromePlaylistViewModel.LoadState.Idle,
                NavidromePlaylistViewModel.LoadState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading playlists…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                NavidromePlaylistViewModel.LoadState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                text = "No playlists yet",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Tap + to create your first playlist.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is NavidromePlaylistViewModel.LoadState.Error -> {
                    val message = (playlistsState as NavidromePlaylistViewModel.LoadState.Error).message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Text(
                                text = "Couldn't load playlists",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { viewModel.retryPlaylists() }) { Text("Retry") }
                        }
                    }
                }

                NavidromePlaylistViewModel.LoadState.Loaded -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist.id) },
                                onRename = { renameTarget = playlist },
                                onDelete = { deleteTarget = playlist },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }

                        // Space for FAB so last item isn't hidden behind it.
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }

    // Create new playlist dialog.
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // Rename playlist dialog.
    renameTarget?.let { playlist ->
        RenamePlaylistDialog(
            current = playlist.name,
            onConfirm = { newName ->
                viewModel.renamePlaylist(playlist, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // Delete confirmation dialog.
    deleteTarget?.let { playlist ->
        DeletePlaylistDialog(
            playlistName = playlist.name,
            onConfirm = {
                viewModel.deletePlaylist(playlist)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: NavidromePlaylist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.songCount > 0) {
                Text(
                    text = "${playlist.songCount} song${if (playlist.songCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Overflow: rename / delete actions.
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (showMenu) {
                androidx.compose.material3.DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { showMenu = false; onRename() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
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
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenamePlaylistDialog(
    current: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Playlist") },
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
                enabled = name.isNotBlank() && name != current,
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeletePlaylistDialog(
    playlistName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Playlist?") },
        text = {
            Text(
                "\"$playlistName\" will be permanently deleted from Navidrome.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
