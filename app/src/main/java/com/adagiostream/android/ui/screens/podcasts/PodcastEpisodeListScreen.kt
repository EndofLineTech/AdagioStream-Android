package com.adagiostream.android.ui.screens.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.service.audiobookshelf.PodcastProgressHydrator
import com.adagiostream.android.service.audiobookshelf.episodeProgressState
import com.adagiostream.android.ui.screens.audiobooks.AbsLoadState

/**
 * One podcast show's episode list (beads_adagio-59p.2.1), sorted per the
 * episode-order setting, with per-row progress hydrated on visibility.
 * Tapping an episode plays it (beads_adagio-59p.2.2) via
 * [PodcastEpisodeListViewModel.playEpisode].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodeListScreen(
    viewModel: PodcastEpisodeListViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val state by viewModel.episodesState.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val showTitle by viewModel.showTitle.collectAsStateWithLifecycle()
    val episodeProgress by viewModel.episodeProgress.collectAsStateWithLifecycle()

    LaunchedEffect(api) {
        if (api != null && state == AbsLoadState.Idle) {
            viewModel.load()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(showTitle ?: "Podcast") },
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
            AbsLoadState.Idle,
            AbsLoadState.Loading,
            -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading episodes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            AbsLoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "This show has no episodes.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is AbsLoadState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load episodes", style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retry() }) { Text("Retry") }
                    }
                }
            }

            AbsLoadState.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(episodes, key = { it.id }) { episode ->
                    // Composition == visibility in a LazyColumn: each row asks
                    // for its own hydration once (deduped in the hydrator).
                    LaunchedEffect(Unit) { viewModel.onEpisodeVisible(episode.id) }
                    val key = PodcastProgressHydrator.key(viewModel.itemId, episode.id)
                    PodcastEpisodeRow(
                        episode = episode,
                        progressState = if (key in episodeProgress) {
                            episodeProgressState(episodeProgress[key])
                        } else {
                            null
                        },
                        onClick = { viewModel.playEpisode(episode.id) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 36.dp))
                }
            }
        }
    }
}
