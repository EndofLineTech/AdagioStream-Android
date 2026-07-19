package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Storage-management screen (baw.6.2) — lists downloaded content with per-item
 * size and delete, plus the total and a "Delete All". Reached from
 * Settings → "Downloaded Music".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    viewModel: DownloadsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val items by viewModel.storageItems.collectAsStateWithLifecycle()
    val audiobooks by viewModel.audiobookItems.collectAsStateWithLifecycle()
    val totalBytes by viewModel.totalBytes.collectAsStateWithLifecycle()
    var showDeleteAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloaded Music") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Total used", style = MaterialTheme.typography.bodyMedium)
                Text(
                    viewModel.formatSize(totalBytes),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Button(
                onClick = { showDeleteAll = true },
                enabled = items.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Delete All")
            }
        }

        HorizontalDivider()

        if (items.isEmpty() && audiobooks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No downloaded music yet.\nTap the download icon on any track or album.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.trackId }) { item ->
                    DownloadedRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        sizeText = viewModel.formatSize(item.sizeBytes),
                        onDelete = { viewModel.delete(item.trackId) },
                    )
                }
                if (audiobooks.isNotEmpty()) {
                    item(key = "audiobooks-header") {
                        Text(
                            text = "Audiobooks",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(audiobooks, key = { "abs:${it.libraryItemId}" }) { book ->
                        DownloadedRow(
                            title = book.title,
                            subtitle = book.author,
                            sizeText = viewModel.formatSize(book.sizeBytes),
                            onDelete = { viewModel.deleteAudiobook(book.libraryItemId) },
                        )
                    }
                }
            }
        }
    }

    // ponytail: "Delete All" covers music only (as its dialog says); audiobooks
    // delete per book — add a bulk action if books ever pile up.
    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Delete all downloads?") },
            text = { Text("All downloaded music will be removed from this device. You can re-download anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    showDeleteAll = false
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }) { Text("Cancel") }
            },
        )
    }
}

/** One downloaded-content row: title/subtitle, size, delete (music + audiobooks). */
@Composable
private fun DownloadedRow(
    title: String,
    subtitle: String,
    sizeText: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = sizeText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete download",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}
