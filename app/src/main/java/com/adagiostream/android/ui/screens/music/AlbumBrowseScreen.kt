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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.AlbumListType
import com.adagiostream.android.service.navidrome.NavidromeCoverArtRequest

/**
 * Album browse screen — `getAlbumList2` with a selectable ordering (baw.9.1).
 *
 * Shows a segmented picker for [AlbumListType] (Newest / Recent / Frequent /
 * Random / A-Z) above a list of albums. Tapping an album navigates to the
 * existing [AlbumDetailScreen] via [onAlbumClick] — this screen never renders
 * album detail itself.
 *
 * Mirrors the structure of [GenreBrowseScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumBrowseScreen(
    viewModel: NavidromeLibraryViewModel = hiltViewModel(),
    onAlbumClick: (albumId: String) -> Unit,
    onBack: () -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val browseState by viewModel.albumBrowseState.collectAsStateWithLifecycle()
    val albums by viewModel.albumBrowseList.collectAsStateWithLifecycle()
    val listType by viewModel.albumListType.collectAsStateWithLifecycle()

    LaunchedEffect(api) {
        if (api != null && browseState == NavidromeLibraryViewModel.LoadState.Idle) {
            viewModel.loadAlbumBrowseList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Albums") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        AlbumListTypePicker(
            selected = listType,
            onSelect = { viewModel.selectAlbumListType(it) },
        )

        when (browseState) {
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
                        text = "No albums found in your Navidrome library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is NavidromeLibraryViewModel.LoadState.Error -> {
                val message = (browseState as NavidromeLibraryViewModel.LoadState.Error).message
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
                        Button(onClick = { viewModel.retryAlbumBrowseList() }) { Text("Retry") }
                    }
                }
            }

            NavidromeLibraryViewModel.LoadState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(albums, key = { it.id }) { album ->
                        AlbumBrowseRow(
                            album = album,
                            api = api,
                            onClick = { onAlbumClick(album.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
    }
}

/** Maps each [AlbumListType] the PO asked for onto a short display label. */
private val albumListTypeLabels = listOf(
    AlbumListType.NEWEST to "Newest",
    AlbumListType.RECENT to "Recent",
    AlbumListType.FREQUENT to "Frequent",
    AlbumListType.RANDOM to "Random",
    AlbumListType.ALPHABETICAL_BY_NAME to "A-Z",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumListTypePicker(
    selected: AlbumListType,
    onSelect: (AlbumListType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        albumListTypeLabels.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = albumListTypeLabels.size),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AlbumBrowseRow(
    album: Album,
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
        if (api != null && album.coverArt != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(NavidromeCoverArtRequest(api = api, coverArtId = album.coverArt, size = 80))
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (album.year != null) {
                        append(album.year)
                        append(" · ")
                    }
                    append(album.trackCount)
                    append(if (album.trackCount == 1) " track" else " tracks")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
