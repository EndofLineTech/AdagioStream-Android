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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromeSearchResult
import com.adagiostream.android.service.navidrome.Track

/**
 * Full-text search screen (baw.4.1).
 *
 * Search bar with 300ms debounce (managed by [NavidromeSearchViewModel]).
 * Results are sectioned into Artists / Albums / Songs.
 *
 * Navigation:
 *  - Tap artist → [onArtistClick]
 *  - Tap album  → [onAlbumClick]
 *  - Tap song   → [NavidromeSearchViewModel.playSearchTrack] (plays all search
 *    songs as a queue from the tapped index, mirroring the E3 album bridge)
 *  - Tap "⋮" on song → [AddToPlaylistSheet] bottom sheet (baw.4.3)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsScreen(
    viewModel: NavidromeSearchViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    // trackId currently waiting for "Add to Playlist" bottom sheet (null = sheet hidden)
    var addToPlaylistTrackId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            title = {
                TextField(
                    value = query,
                    onValueChange = { viewModel.onQueryChanged(it) },
                    placeholder = { Text("Search music…") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            actions = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearSearch() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
        )

        when (val state = searchState) {
            NavidromeSearchViewModel.SearchState.Idle -> {
                // Empty prompt when no query has been entered yet.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "Search for artists, albums, and songs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromeSearchViewModel.SearchState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Searching…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromeSearchViewModel.SearchState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No results for \"$query\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromeSearchViewModel.SearchState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = "Search failed",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is NavidromeSearchViewModel.SearchState.Loaded -> {
                searchResults?.let { results ->
                    SearchResultList(
                        results = results,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onTrackClick = { viewModel.playSearchTrack(it) },
                        onTrackAddToPlaylist = { trackId -> addToPlaylistTrackId = trackId },
                    )
                }
            }
        }
    }

    // "Add to Playlist" bottom sheet (baw.4.3).
    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistSheet(
            trackId = trackId,
            onDismiss = { addToPlaylistTrackId = null },
        )
    }
}

@Composable
private fun SearchResultList(
    results: NavidromeSearchResult,
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onTrackClick: (Track) -> Unit,
    onTrackAddToPlaylist: (trackId: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // --- Artists section ---
        if (results.artists.isNotEmpty()) {
            item {
                SearchSectionHeader(title = "Artists")
            }
            items(results.artists, key = { "artist-${it.id}" }) { artist ->
                SearchArtistRow(
                    artist = artist,
                    onClick = { onArtistClick(artist.id) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }

        // --- Albums section ---
        if (results.albums.isNotEmpty()) {
            item {
                SearchSectionHeader(title = "Albums")
            }
            items(results.albums, key = { "album-${it.id}" }) { album ->
                SearchAlbumRow(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }

        // --- Songs section ---
        if (results.tracks.isNotEmpty()) {
            item {
                SearchSectionHeader(title = "Songs")
            }
            items(results.tracks, key = { "track-${it.id}" }) { track ->
                SearchTrackRow(
                    track = track,
                    onClick = { onTrackClick(track) },
                    onAddToPlaylist = { onTrackAddToPlaylist(track.id) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun SearchArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.albumCount > 0) {
                Text(
                    text = "${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchAlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (album.year != null) {
                Text(
                    text = album.year.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchTrackRow(
    track: Track,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
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

        if (track.duration != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatSearchDuration(track.duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // "Add to Playlist" overflow button (baw.4.3).
        IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Add to playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatSearchDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
