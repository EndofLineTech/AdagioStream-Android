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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.adagiostream.android.service.navidrome.SubsonicGenre
import com.adagiostream.android.service.navidrome.Track

/**
 * Genre detail screen — shows songs for a single genre (baw.2.4).
 *
 * Receives the `genreName` from the nav back-stack argument (URL-decoded).
 * Delegates loading to [NavidromeLibraryViewModel.loadSongsByGenre].
 *
 * PLAYBACK (baw.2.4 + baw.3.8): Tapping a track enqueues the WHOLE loaded
 * genre track list and starts from the tapped index, matching the same
 * browse-to-play bridge added in baw.3.8 for album tracks. The tap delegates
 * to [NavidromeLibraryViewModel.playGenreTrack].
 *
 * Track rows show the artist NAME from [Track.artist] — never [Track.artistId].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreDetailScreen(
    viewModel: NavidromeLibraryViewModel = hiltViewModel(),
    onBack: () -> Unit,
    backStackEntry: NavBackStackEntry? = null,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val genreTracksState by viewModel.genreTracksState.collectAsStateWithLifecycle()
    val tracks by viewModel.genreTracks.collectAsStateWithLifecycle()

    // Genre name comes from nav args (URL-decoded by NavBackStackEntry).
    val genreNameArg = backStackEntry?.arguments?.getString("genreName")

    LaunchedEffect(api, genreNameArg) {
        if (api == null || genreNameArg == null) return@LaunchedEffect
        // Build a minimal SubsonicGenre to hand to the ViewModel. songCount/albumCount
        // are metadata-only (display hints); they don't affect the load call.
        val genre = viewModel.genres.value.firstOrNull { it.name == genreNameArg }
            ?: SubsonicGenre(name = genreNameArg, songCount = 0, albumCount = 0)
        if (genreTracksState == NavidromeLibraryViewModel.LoadState.Idle ||
            selectedGenre?.name != genreNameArg
        ) {
            viewModel.loadSongsByGenre(genre)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(selectedGenre?.name ?: genreNameArg ?: "Genre") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        when (genreTracksState) {
            NavidromeLibraryViewModel.LoadState.Idle,
            NavidromeLibraryViewModel.LoadState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading songs…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${selectedGenre?.name ?: "This genre"} has no songs in your library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromeLibraryViewModel.LoadState.Error -> {
                val message = (genreTracksState as NavidromeLibraryViewModel.LoadState.Error).message
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load songs", style = MaterialTheme.typography.titleMedium)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retryGenreTracks() }) { Text("Retry") }
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(tracks, key = { it.id }) { track ->
                        GenreTrackRow(
                            track = track,
                            onClick = { viewModel.playGenreTrack(track) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

/**
 * A single track row for the genre detail screen.
 *
 * Shows title + artist NAME (from [Track.artist], the human-readable name from
 * the Subsonic "artist" field) — never [Track.artistId].  Different genres
 * contain tracks from multiple artists, so the artist name is always surfaced here.
 */
@Composable
private fun GenreTrackRow(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Show artist NAME — never artistId.
            // This is the "artist" field from the Subsonic getSongsByGenre response.
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

        if (track.duration != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatGenreDuration(track.duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatGenreDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
