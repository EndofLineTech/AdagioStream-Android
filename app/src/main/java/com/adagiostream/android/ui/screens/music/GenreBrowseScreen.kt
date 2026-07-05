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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
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
import com.adagiostream.android.service.navidrome.SubsonicGenre

/**
 * Genre browse screen — lists all available genres (baw.2.4).
 *
 * Tapping a genre navigates to [GenreDetailScreen] via [onGenreClick].
 * Mirrors the structure of [MusicLibraryScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreBrowseScreen(
    viewModel: NavidromeLibraryViewModel = hiltViewModel(),
    onGenreClick: (genreName: String) -> Unit,
    onBack: () -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val genresState by viewModel.genresState.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()

    LaunchedEffect(api) {
        if (api != null && genresState == NavidromeLibraryViewModel.LoadState.Idle) {
            viewModel.loadGenres()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Genres") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        when (genresState) {
            NavidromeLibraryViewModel.LoadState.Idle,
            NavidromeLibraryViewModel.LoadState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading genres…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No genres found in your Navidrome library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromeLibraryViewModel.LoadState.Error -> {
                val message = (genresState as NavidromeLibraryViewModel.LoadState.Error).message
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load genres", style = MaterialTheme.typography.titleMedium)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retryGenres() }) { Text("Retry") }
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(genres, key = { it.name }) { genre ->
                        GenreRow(
                            genre = genre,
                            onClick = { onGenreClick(genre.name) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreRow(genre: SubsonicGenre, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = genre.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = buildString {
                    append(genre.songCount)
                    append(if (genre.songCount == 1) " song" else " songs")
                    if (genre.albumCount > 0) {
                        append(" · ")
                        append(genre.albumCount)
                        append(if (genre.albumCount == 1) " album" else " albums")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
