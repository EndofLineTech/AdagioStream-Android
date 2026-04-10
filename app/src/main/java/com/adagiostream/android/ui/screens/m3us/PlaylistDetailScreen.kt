package com.adagiostream.android.ui.screens.m3us

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.CustomPlaylistEntry
import com.adagiostream.android.model.CustomPlaylistGroup
import com.adagiostream.android.ui.components.RetryableAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var addEntryToGroup by remember { mutableStateOf<CustomPlaylistGroup?>(null) }
    var renameGroupTarget by remember { mutableStateOf<CustomPlaylistGroup?>(null) }

    val currentPlaylist = playlist
    if (currentPlaylist == null) {
        onBack()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPlaylist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddGroupDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Add group")
                    }
                    IconButton(onClick = {
                        val m3u = viewModel.exportAsM3U()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/x-mpegurl"
                            putExtra(Intent.EXTRA_TEXT, m3u)
                            putExtra(Intent.EXTRA_SUBJECT, "${currentPlaylist.name}.m3u")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Playlist"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share as M3U")
                    }
                },
            )
        },
    ) { padding ->
        if (currentPlaylist.groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No groups yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { showAddGroupDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Group")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                for (group in currentPlaylist.groups) {
                    item(key = "header_${group.id}") {
                        GroupHeader(
                            group = group,
                            onAddEntry = { addEntryToGroup = group },
                            onRename = { renameGroupTarget = group },
                            onDelete = { viewModel.deleteGroup(group.id) },
                        )
                    }
                    items(group.entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            onPlay = { viewModel.playEntry(entry) },
                            onDelete = { viewModel.removeEntry(entry.id, group.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAddGroupDialog) {
        TextInputDialog(
            title = "New Group",
            label = "Group name",
            confirmText = "Create",
            onConfirm = { name ->
                viewModel.addGroup(name)
                showAddGroupDialog = false
            },
            onDismiss = { showAddGroupDialog = false },
        )
    }

    renameGroupTarget?.let { group ->
        TextInputDialog(
            title = "Rename Group",
            label = "Group name",
            initialValue = group.name,
            confirmText = "Rename",
            onConfirm = { name ->
                viewModel.renameGroup(group.id, name)
                renameGroupTarget = null
            },
            onDismiss = { renameGroupTarget = null },
        )
    }

    addEntryToGroup?.let { group ->
        AddManualEntryDialog(
            groupName = group.name,
            onConfirm = { name, url, logoUrl ->
                viewModel.addManualEntry(name, url, logoUrl, group.id)
                addEntryToGroup = null
            },
            onDismiss = { addEntryToGroup = null },
        )
    }
}

@Composable
private fun GroupHeader(
    group: CustomPlaylistGroup,
    onAddEntry: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${group.entries.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onAddEntry) {
            Icon(Icons.Default.Add, contentDescription = "Add stream", modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Options", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { showMenu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete Group") },
                    onClick = { showMenu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: CustomPlaylistEntry,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.logoURL != null) {
                RetryableAsyncImage(
                    model = entry.logoURL,
                    contentDescription = entry.name,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.Radio,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.streamURL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AddManualEntryDialog(
    groupName: String,
    onConfirm: (name: String, url: String, logoUrl: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var logoUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Stream to $groupName") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Stream name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = logoUrl,
                    onValueChange = { logoUrl = it },
                    label = { Text("Logo URL (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim(), logoUrl.trim().ifBlank { null }) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
