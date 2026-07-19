package com.adagiostream.android.ui.screens.podcasts

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.ui.screens.podcasts.PodcastsViewModel.LibrariesState

/**
 * Podcasts entry screen (beads_adagio-59p.2.1).
 *
 * With more than one podcast library this is the library picker; with exactly
 * one it forwards straight to that library's show grid (replacing itself on
 * the back stack so Back returns to the Library tab, not an empty picker) —
 * same pattern as [com.adagiostream.android.ui.screens.audiobooks.AudiobooksScreen].
 *
 * @param onOpenLibrary navigate to the show grid; `replace = true` means this
 *   screen should be popped (the single-library auto-forward).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(
    viewModel: PodcastsViewModel = hiltViewModel(),
    onOpenLibrary: (libraryId: String, replace: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val state by viewModel.librariesState.collectAsStateWithLifecycle()

    LaunchedEffect(api) {
        if (api != null && state == LibrariesState.Idle) {
            viewModel.loadLibraries()
        }
    }

    // Single podcast library — skip the picker entirely.
    LaunchedEffect(state) {
        (state as? LibrariesState.Single)?.let { onOpenLibrary(it.library.id, true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Podcasts") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        when (val s = state) {
            // Single also renders the spinner for the frame before the
            // auto-forward above lands.
            LibrariesState.Idle,
            LibrariesState.Loading,
            is LibrariesState.Single,
            -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading libraries…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            LibrariesState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No podcast libraries on your Audiobookshelf server.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is LibrariesState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load libraries", style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retry() }) { Text("Retry") }
                    }
                }
            }

            is LibrariesState.Picker -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(s.libraries, key = { it.id }) { library ->
                        LibraryRow(
                            library = library,
                            onClick = { onOpenLibrary(library.id, false) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(library: AbsLibrary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Podcasts,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = library.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
