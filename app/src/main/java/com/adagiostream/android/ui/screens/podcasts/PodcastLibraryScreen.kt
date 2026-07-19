package com.adagiostream.android.ui.screens.podcasts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.material3.LinearProgressIndicator
import com.adagiostream.android.service.audiobookshelf.AbsMediaProgress
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.EpisodeProgressState
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeEntry
import com.adagiostream.android.service.audiobookshelf.PodcastProgressHydrator
import com.adagiostream.android.service.audiobookshelf.PodcastShow
import com.adagiostream.android.service.audiobookshelf.episodeProgressState
import com.adagiostream.android.ui.screens.audiobooks.AbsLoadState
import com.adagiostream.android.ui.screens.audiobooks.AudiobookCover
import com.adagiostream.android.ui.screens.podcasts.PodcastLibraryViewModel.BrowseMode

/**
 * One podcast library (beads_adagio-59p.2.1): a By Show | Recent Episodes
 * mode toggle (iOS puts this in a nav-bar menu; a segmented row is the
 * Material-native equivalent), a Continue Listening episode shelf atop the
 * show grid, and the flat recent-episodes list.
 *
 * Tapping a show opens its episode list. Tapping an episode ANYWHERE (recent
 * list, shelf) plays/resumes it (beads_adagio-59p.2.2) via
 * [PodcastLibraryViewModel.playEpisode].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastLibraryScreen(
    viewModel: PodcastLibraryViewModel = hiltViewModel(),
    onShowClick: (itemId: String) -> Unit,
    onBack: () -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val browseMode by viewModel.browseMode.collectAsStateWithLifecycle()
    val showsState by viewModel.showsState.collectAsStateWithLifecycle()
    val shows by viewModel.shows.collectAsStateWithLifecycle()
    val recentState by viewModel.recentState.collectAsStateWithLifecycle()
    val recentEpisodes by viewModel.recentEpisodes.collectAsStateWithLifecycle()
    val continueListening by viewModel.continueListening.collectAsStateWithLifecycle()
    val episodeProgress by viewModel.episodeProgress.collectAsStateWithLifecycle()

    LaunchedEffect(api) {
        if (api != null && showsState == AbsLoadState.Idle) {
            viewModel.load()
        }
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

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            BrowseMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = browseMode == mode,
                    onClick = { viewModel.setBrowseMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = BrowseMode.entries.size,
                    ),
                ) {
                    Text(mode.displayName)
                }
            }
        }

        when (browseMode) {
            BrowseMode.BY_SHOW -> ShowGrid(
                state = showsState,
                shows = shows,
                continueListening = continueListening,
                episodeProgress = episodeProgress,
                api = api,
                onShowClick = onShowClick,
                onEntryClick = viewModel::playEpisode,
                onEpisodeVisible = viewModel::onEpisodeVisible,
                onRetry = { viewModel.retry() },
            )

            BrowseMode.RECENT -> RecentEpisodeList(
                state = recentState,
                entries = recentEpisodes,
                episodeProgress = episodeProgress,
                onEntryClick = viewModel::playEpisode,
                onEpisodeVisible = viewModel::onEpisodeVisible,
                onRetry = { viewModel.retryRecent() },
            )
        }
    }
}

@Composable
private fun ShowGrid(
    state: AbsLoadState,
    shows: List<PodcastShow>,
    continueListening: List<PodcastEpisodeEntry>,
    episodeProgress: Map<String, AbsMediaProgress?>,
    api: AudiobookshelfApi?,
    onShowClick: (itemId: String) -> Unit,
    onEntryClick: (PodcastEpisodeEntry) -> Unit,
    onEpisodeVisible: (showId: String, episodeId: String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        AbsLoadState.Idle,
        AbsLoadState.Loading,
        -> LoadingBox("Loading podcasts…")

        AbsLoadState.Empty -> MessageBox("No podcasts in this library.")

        is AbsLoadState.Error -> ErrorBox("Couldn't load podcasts", state.message, onRetry)

        AbsLoadState.Loaded -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (continueListening.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            text = "Continue Listening",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ContinueListeningShelf(
                            entries = continueListening,
                            episodeProgress = episodeProgress,
                            api = api,
                            onEntryClick = onEntryClick,
                            onEpisodeVisible = onEpisodeVisible,
                        )
                        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }

            items(shows, key = { it.id }) { show ->
                ShowTile(show = show, api = api, onClick = { onShowClick(show.id) })
            }
        }
    }
}

/**
 * Horizontal shelf of in-progress podcast episodes, mirroring the audiobooks
 * shelf. Tap resumes the episode (beads_adagio-59p.2.2).
 */
@Composable
private fun ContinueListeningShelf(
    entries: List<PodcastEpisodeEntry>,
    episodeProgress: Map<String, AbsMediaProgress?>,
    api: AudiobookshelfApi?,
    onEntryClick: (PodcastEpisodeEntry) -> Unit,
    onEpisodeVisible: (showId: String, episodeId: String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(entries, key = { "${it.showLibraryItemId}/${it.episode.id}" }) { entry ->
            LaunchedEffect(Unit) {
                onEpisodeVisible(entry.showLibraryItemId, entry.episode.id)
            }
            Column(
                modifier = Modifier
                    .width(120.dp)
                    .clickable(onClick = { onEntryClick(entry) }),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AudiobookCover(
                    api = api,
                    itemId = entry.showLibraryItemId,
                    size = 120.dp,
                    coverWidth = 240,
                )
                Text(
                    text = entry.episode.title ?: "Episode",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.showTitle?.let { show ->
                    Text(
                        text = show,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val key = PodcastProgressHydrator.key(entry.showLibraryItemId, entry.episode.id)
                val state = if (key in episodeProgress) {
                    episodeProgressState(episodeProgress[key])
                } else {
                    null
                }
                if (state is EpisodeProgressState.InProgress) {
                    LinearProgressIndicator(
                        progress = { state.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowTile(show: PodcastShow, api: AudiobookshelfApi?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AudiobookCover(
            api = api,
            itemId = show.id,
            size = 140.dp,
            coverWidth = 280,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = show.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        show.author?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentEpisodeList(
    state: AbsLoadState,
    entries: List<PodcastEpisodeEntry>,
    episodeProgress: Map<String, AbsMediaProgress?>,
    onEntryClick: (PodcastEpisodeEntry) -> Unit,
    onEpisodeVisible: (showId: String, episodeId: String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        AbsLoadState.Idle,
        AbsLoadState.Loading,
        -> LoadingBox("Loading recent episodes…")

        AbsLoadState.Empty -> MessageBox("Your podcast library has no episodes yet.")

        is AbsLoadState.Error -> ErrorBox("Couldn't load episodes", state.message, onRetry)

        AbsLoadState.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { "${it.showLibraryItemId}/${it.episode.id}" }) { entry ->
                // Composition == visibility in a LazyColumn: each row asks for
                // its own progress hydration once (deduped in the hydrator).
                LaunchedEffect(Unit) {
                    onEpisodeVisible(entry.showLibraryItemId, entry.episode.id)
                }
                val key = PodcastProgressHydrator.key(entry.showLibraryItemId, entry.episode.id)
                PodcastEpisodeRow(
                    episode = entry.episode,
                    progressState = if (key in episodeProgress) {
                        episodeProgressState(episodeProgress[key])
                    } else {
                        null
                    },
                    showTitle = entry.showTitle,
                    onClick = { onEntryClick(entry) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 36.dp))
            }
        }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun ErrorBox(title: String, message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
