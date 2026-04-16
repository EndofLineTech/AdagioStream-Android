package com.adagiostream.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.CustomPlaylistEntry
import com.adagiostream.android.model.CustomPlaylistGroup
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.ui.screens.m3us.TextInputDialog
import com.adagiostream.android.ui.theme.AdagioStreamTheme
import com.adagiostream.android.util.UrlSanitizer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var playlistManager: CustomPlaylistManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrl() ?: run {
            finish()
            return
        }

        setContent {
            AdagioStreamTheme {
                ShareReceiverScreen(
                    url = sharedUrl,
                    playlistManager = playlistManager,
                    onDone = { finish() },
                )
            }
        }
    }

    private fun extractUrl(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return null
        return if (UrlSanitizer.isHttpUrl(url)) url else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareReceiverScreen(
    url: String,
    playlistManager: CustomPlaylistManager,
    onDone: () -> Unit,
) {
    val playlists by playlistManager.playlists.collectAsStateWithLifecycle()
    var selectedPlaylist by remember { mutableStateOf<CustomPlaylist?>(null) }
    var streamName by remember { mutableStateOf(url.substringAfterLast("/").substringBefore("?").ifBlank { "Shared Stream" }) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add to Playlist") },
                actions = {
                    TextButton(onClick = onDone) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = streamName,
                onValueChange = { streamName = it },
                label = { Text("Stream name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(16.dp))

            val playlist = selectedPlaylist
            if (playlist == null) {
                Text("Select playlist:", style = MaterialTheme.typography.labelMedium)
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
                            Text(p.name)
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
                Text("Select group in \"${playlist.name}\":", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { selectedPlaylist = null }) {
                    Text("< Back")
                }
                LazyColumn {
                    items(playlist.groups) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val entry = CustomPlaylistEntry(
                                        name = streamName.trim().ifBlank { "Shared Stream" },
                                        streamURL = url,
                                    )
                                    playlistManager.addEntry(entry, group.id, playlist.id)
                                    onDone()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(group.name)
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
        }
    }

    if (showCreatePlaylist) {
        TextInputDialog(
            title = "New Playlist",
            label = "Playlist name",
            confirmText = "Create",
            onConfirm = { name ->
                selectedPlaylist = playlistManager.createPlaylist(name)
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
                    val group = playlistManager.addGroup(name, playlist.id)
                    if (group != null) {
                        val entry = CustomPlaylistEntry(
                            name = streamName.trim().ifBlank { "Shared Stream" },
                            streamURL = url,
                        )
                        playlistManager.addEntry(entry, group.id, playlist.id)
                        onDone()
                    }
                    showCreateGroup = false
                },
                onDismiss = { showCreateGroup = false },
            )
        }
    }
}
