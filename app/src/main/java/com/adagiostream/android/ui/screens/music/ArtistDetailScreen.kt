package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.NavidromeCoverArtRequest

/**
 * Artist detail screen — shows an album grid for a single artist (baw.2.3).
 *
 * Receives the `artistId` from the nav back-stack arguments and delegates
 * to [NavidromeLibraryViewModel.loadAlbums].  Tapping an album navigates
 * via [onAlbumClick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    viewModel: NavidromeLibraryViewModel = hiltViewModel(),
    onAlbumClick: (albumId: String) -> Unit,
    onBack: () -> Unit,
    backStackEntry: NavBackStackEntry? = null,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val selectedArtist by viewModel.selectedArtist.collectAsStateWithLifecycle()
    val albumsState by viewModel.albumsState.collectAsStateWithLifecycle()
    val artistAlbums by viewModel.artistAlbums.collectAsStateWithLifecycle()

    // The artistId is passed via nav argument; look it up from artists list
    // once the VM is ready (the artist object is already in memory from MusicLibraryScreen).
    val artistIdArg = backStackEntry?.arguments?.getString("artistId")

    LaunchedEffect(api, artistIdArg) {
        if (api == null) return@LaunchedEffect
        // Re-resolve the artist from the artists StateFlow if not already loaded
        val artists = viewModel.artists.value
        val artist = artists.firstOrNull { it.id == artistIdArg }
            ?: selectedArtist
            ?: return@LaunchedEffect
        if (albumsState == NavidromeLibraryViewModel.LoadState.Idle ||
            selectedArtist?.id != artist.id
        ) {
            viewModel.loadAlbums(artist)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(selectedArtist?.name ?: "Artist") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        when (albumsState) {
            NavidromeLibraryViewModel.LoadState.Idle,
            NavidromeLibraryViewModel.LoadState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading albums…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${selectedArtist?.name ?: "This artist"} has no albums in your library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromeLibraryViewModel.LoadState.Error -> {
                val message = (albumsState as NavidromeLibraryViewModel.LoadState.Error).message
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load albums", style = MaterialTheme.typography.titleMedium)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retryAlbums() }) { Text("Retry") }
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Loaded -> {
                AlbumGrid(
                    albums = artistAlbums,
                    api = api,
                    onAlbumClick = onAlbumClick,
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    api: com.adagiostream.android.service.navidrome.NavidromeApi?,
    onAlbumClick: (albumId: String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumGridCell(
                album = album,
                api = api,
                onClick = { onAlbumClick(album.id) },
            )
        }
    }
}

@Composable
private fun AlbumGridCell(
    album: Album,
    api: com.adagiostream.android.service.navidrome.NavidromeApi?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        // Square cover art
        if (api != null && album.coverArt != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(NavidromeCoverArtRequest(api = api, coverArtId = album.coverArt, size = 300))
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        Text(
            text = album.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (album.year != null) {
            Text(
                text = album.year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
