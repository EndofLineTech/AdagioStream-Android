package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromeCoverArtRequest

/**
 * Root Music tab screen (baw.2.3, baw.2.5).
 *
 * Shows a "Browse by Genre" shortcut followed by the artist list when a
 * Navidrome account is configured, or an empty-state prompt directing the
 * user to Settings → Accounts when not.
 *
 * Navigation: tapping an artist row navigates to [ArtistDetailScreen] via
 * [onArtistClick]; tapping the Genres row navigates to [GenreBrowseScreen]
 * via [onGenresClick].  Callers own the NavController.
 */
@Composable
fun MusicLibraryScreen(
    viewModel: NavidromeLibraryViewModel = hiltViewModel(),
    onArtistClick: (artistId: String) -> Unit,
    onGenresClick: () -> Unit = {},
    onAlbumsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onPlaylistsClick: () -> Unit = {},
    onAddAccountClick: () -> Unit = {},
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val artistsState by viewModel.artistsState.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()
    val downloadedTracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()

    // Load artists on first appearance when an API is available (suppressed in
    // offline mode — baw.12).
    LaunchedEffect(api, offlineMode) {
        if (!offlineMode && api != null && artistsState == NavidromeLibraryViewModel.LoadState.Idle) {
            viewModel.loadArtists()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Music",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            // Search is network-only — hidden in offline mode (baw.12).
            if (api != null && !offlineMode) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        when {
            // No account configured takes precedence over the offline banner
            // (beads_adagio-15x.3) — the Library tab is now always visible, and
            // without an account there's nothing to browse online or offline.
            api == null -> NoAccountEmptyState(onAddAccountClick = onAddAccountClick)

            // Offline mode replaces the whole browse UI with the downloaded-only
            // list under a persistent banner (baw.12 — mirrors iOS offlineBrowser).
            offlineMode -> OfflineLibrary(
                tracks = downloadedTracks,
                onTrackClick = { viewModel.playDownloadedTrack(it) },
            )

            artistsState == NavidromeLibraryViewModel.LoadState.Loading ||
                artistsState == NavidromeLibraryViewModel.LoadState.Idle -> {
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
                            text = "Loading artists…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            artistsState == NavidromeLibraryViewModel.LoadState.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No artists found in your Navidrome library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            artistsState is NavidromeLibraryViewModel.LoadState.Error -> {
                val message = (artistsState as NavidromeLibraryViewModel.LoadState.Error).message
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "Couldn't load artists",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retryArtists() }) {
                            Text("Retry")
                        }
                    }
                }
            }

            else -> { // Loaded
                ArtistList(
                    artists = artists,
                    api = api,
                    onArtistClick = onArtistClick,
                    onGenresClick = onGenresClick,
                    onAlbumsClick = onAlbumsClick,
                    onPlaylistsClick = onPlaylistsClick,
                )
            }
        }
    }
}

/**
 * Offline-mode library (baw.12): persistent banner + downloaded-only track list.
 * Copy mirrors iOS MusicLibraryView's offlineBrowser.
 */
@Composable
private fun OfflineLibrary(
    tracks: List<com.adagiostream.android.service.navidrome.Track>,
    onTrackClick: (com.adagiostream.android.service.navidrome.Track) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Persistent banner — not dismissible, matching iOS.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Offline mode — showing downloaded music",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "No Downloads",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "No downloaded tracks available. Turn off offline mode or download tracks first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(tracks, key = { it.id }) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = { onTrackClick(track) })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            track.artist?.let { artist ->
                                Text(
                                    text = artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                }
            }
        }
    }
}

/**
 * Shown when no Subsonic/Navidrome account is configured (beads_adagio-15x.3).
 * The Library tab is always visible now, so this is the first thing most
 * users see here — the button jumps straight to account setup rather than
 * making them find Settings → Accounts themselves.
 */
@Composable
private fun NoAccountEmptyState(onAddAccountClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "No Music Library",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Add a Navidrome or Subsonic server to browse your music library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddAccountClick) {
                Text("Add Account")
            }
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<Artist>,
    api: com.adagiostream.android.service.navidrome.NavidromeApi?,
    onArtistClick: (artistId: String) -> Unit,
    onGenresClick: () -> Unit,
    onAlbumsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Browse-by-album-list shortcut (baw.9.1) — shown above the artist list.
        item {
            AlbumsBrowseRow(onClick = onAlbumsClick)
            HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
        }

        // Browse-by-genre shortcut (baw.2.4) — shown above the artist list.
        item {
            GenresBrowseRow(onClick = onGenresClick)
            HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
        }

        // Browse Navidrome playlists shortcut (baw.4.2).
        item {
            PlaylistsBrowseRow(onClick = onPlaylistsClick)
            HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
        }

        items(artists, key = { it.id }) { artist ->
            ArtistRow(
                artist = artist,
                api = api,
                onClick = { onArtistClick(artist.id) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
        }
    }
}

@Composable
private fun PlaylistsBrowseRow(onClick: () -> Unit) {
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
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "Playlists",
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

@Composable
private fun AlbumsBrowseRow(onClick: () -> Unit) {
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
                imageVector = Icons.Filled.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "Albums",
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

@Composable
private fun GenresBrowseRow(onClick: () -> Unit) {
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
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "Browse by Genre",
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

@Composable
private fun ArtistRow(
    artist: Artist,
    api: com.adagiostream.android.service.navidrome.NavidromeApi?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover art thumbnail — stable Coil cache key via NavidromeCoverArtRequest
        if (api != null && artist.coverArt != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(NavidromeCoverArtRequest(api = api, coverArtId = artist.coverArt, size = 80))
                    .build(),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        } else {
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
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            // Display artist NAME — never artistId (iOS bug came from showing the id)
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
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
