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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.adagiostream.android.service.navidrome.NavidromeCoverArtRequest
import com.adagiostream.android.service.navidrome.Track

/**
 * Album detail screen — shows tracks for a single album (baw.2.3).
 *
 * Receives the `albumId` from the nav back-stack arguments and delegates
 * to [NavidromeLibraryViewModel.loadTracks].
 *
 * PLAYBACK (baw.3.8): Tapping a track enqueues the WHOLE loaded album and starts
 * from the tapped index (PO decision), giving auto-advance immediately. The tap
 * delegates to [NavidromeLibraryViewModel.playTrack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    viewModel: NavidromeLibraryViewModel = hiltViewModel(),
    onBack: () -> Unit,
    backStackEntry: NavBackStackEntry? = null,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
    val artistName by viewModel.selectedAlbumArtistName.collectAsStateWithLifecycle()
    val tracksState by viewModel.tracksState.collectAsStateWithLifecycle()
    val tracks by viewModel.albumTracks.collectAsStateWithLifecycle()

    var addToPlaylistTrackId by remember { mutableStateOf<String?>(null) }

    val albumIdArg = backStackEntry?.arguments?.getString("albumId")

    LaunchedEffect(api, albumIdArg) {
        if (api == null) return@LaunchedEffect
        val album = viewModel.artistAlbums.value.firstOrNull { it.id == albumIdArg }
            ?: selectedAlbum
            ?: return@LaunchedEffect
        if (tracksState == NavidromeLibraryViewModel.LoadState.Idle ||
            selectedAlbum?.id != album.id
        ) {
            viewModel.loadTracks(album)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(selectedAlbum?.title ?: "Album") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        when (tracksState) {
            NavidromeLibraryViewModel.LoadState.Idle,
            NavidromeLibraryViewModel.LoadState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading tracks…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${selectedAlbum?.title ?: "This album"} has no tracks in your library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromeLibraryViewModel.LoadState.Error -> {
                val message = (tracksState as NavidromeLibraryViewModel.LoadState.Error).message
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load tracks", style = MaterialTheme.typography.titleMedium)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retryTracks() }) { Text("Retry") }
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Album header
                    item {
                        AlbumHeader(
                            album = selectedAlbum,
                            artistName = artistName,
                            api = api,
                        )
                    }

                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            onClick = { viewModel.playTrack(track) },
                            onToggleStar = { viewModel.toggleStar(track) },
                            onAddToPlaylist = { addToPlaylistTrackId = track.id },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                    }
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
private fun AlbumHeader(
    album: com.adagiostream.android.service.navidrome.Album?,
    artistName: String?,
    api: com.adagiostream.android.service.navidrome.NavidromeApi?,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (api != null && album?.coverArt != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(NavidromeCoverArtRequest(api = api, coverArtId = album.coverArt, size = 600))
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size(200.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        } else {
            Box(
                modifier = Modifier.size(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(80.dp),
                )
            }
        }

        Text(
            text = album?.title ?: "",
            style = MaterialTheme.typography.titleLarge,
        )
        // Show artist NAME (from getAlbum's "artist" field) — never artistId.
        // iOS bug came from threading artistId to the display layer here.
        if (artistName != null) {
            Text(
                text = artistName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (album?.year != null) {
            Text(
                text = album.year.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Track number badge
        Text(
            text = track.trackNumber?.toString() ?: "–",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Play count indicator (baw.5.3)
            if (track.playCount != null && track.playCount > 0) {
                Text(
                    text = "▶ ${track.playCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Star toggle (baw.5.2)
        StarButton(starred = track.starred ?: false, onToggle = onToggleStar)

        if (track.duration != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatDuration(track.duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // "Add to Playlist" overflow (baw.4.3).
        IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Add to playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
