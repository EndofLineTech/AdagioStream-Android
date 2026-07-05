package com.adagiostream.android.ui.screens.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.ArtworkDisplayMode
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.ui.components.BaseballDiamond
import com.adagiostream.android.ui.components.CastButton
import com.adagiostream.android.ui.components.RetryableAsyncImage
import com.adagiostream.android.util.BitrateFormatter
import com.adagiostream.android.util.rememberListeningTime
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    onDismiss: () -> Unit,
    artworkDisplayMode: ArtworkDisplayMode = ArtworkDisplayMode.COVER_ART,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val listeningTimeMs by viewModel.listeningTimeMs.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val trackMetadata by viewModel.currentTrackMetadata.collectAsStateWithLifecycle()
    val isTimeShifted by viewModel.isTimeShifted.collectAsStateWithLifecycle()
    val isTrackLoved by viewModel.isTrackLoved.collectAsStateWithLifecycle()
    val espnGame by viewModel.currentESPNGame.collectAsStateWithLifecycle()
    val epgEntries by viewModel.currentEPGEntries.collectAsStateWithLifecycle()
    val isCasting by viewModel.isCasting.collectAsStateWithLifecycle()
    val castDeviceName by viewModel.castDeviceName.collectAsStateWithLifecycle()
    val isLibrarySource by viewModel.isLibrarySource.collectAsStateWithLifecycle()

    // Library (on-demand) playback is a fundamentally different surface from
    // live radio — no channel, no EPG/ESPN, a real seekable position, and a
    // reorderable queue (baw.9.3 / baw.9.5). Branch out to a dedicated
    // composable rather than threading `channel == null` checks through the
    // entire radio-oriented body below, which stays untouched.
    if (isLibrarySource) {
        LibraryNowPlayingSheet(onDismiss = onDismiss, viewModel = viewModel)
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val channel = currentChannel ?: return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val mainImageUrl = if (artworkDisplayMode == ArtworkDisplayMode.COVER_ART) {
                trackMetadata?.albumArtURL ?: channel.logoURL
            } else {
                channel.logoURL
            }
            val mainImageScale = if (artworkDisplayMode == ArtworkDisplayMode.COVER_ART && trackMetadata?.albumArtURL != null) {
                ContentScale.Crop
            } else {
                ContentScale.Fit
            }
            if (mainImageUrl != null) {
                RetryableAsyncImage(
                    model = mainImageUrl,
                    contentDescription = channel.name,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = mainImageScale,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Radio,
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = channel.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Text(
                text = channel.group,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isCasting && castDeviceName != null) {
                Text(
                    text = "Casting to $castDeviceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val isActive = playbackState is PlaybackState.Playing || playbackState is PlaybackState.CatchingUp
            val listeningTime = rememberListeningTime(accumulatedMs = listeningTimeMs, isPlaying = isActive)
            val statusText = when (playbackState) {
                is PlaybackState.Buffering -> "Buffering..."
                is PlaybackState.Playing -> if (listeningTime != null) "Playing \u00B7 $listeningTime" else "Playing"
                is PlaybackState.Paused -> if (listeningTime != null) "Paused \u00B7 $listeningTime" else "Paused"
                is PlaybackState.CatchingUp -> "Catching up..."
                is PlaybackState.Error -> (playbackState as PlaybackState.Error).message
                else -> ""
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BitrateDisplay(viewModel)

            if (trackMetadata == null && espnGame != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = espnGame!!.nowPlayingTitle,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (espnGame!!.isMLBLive) {
                        BaseballDiamond(
                            onFirst = espnGame!!.onFirst == true,
                            onSecond = espnGame!!.onSecond == true,
                            onThird = espnGame!!.onThird == true,
                            size = 28.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text(
                        text = espnGame!!.nowPlayingSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (trackMetadata == null && espnGame == null) {
                val currentEPG = epgEntries.firstOrNull { it.isCurrentlyAiring }
                if (currentEPG != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentEPG.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (trackMetadata != null) {
                Spacer(modifier = Modifier.height(12.dp))
                // Only show separate album art when in CHANNEL_LOGO mode
                // (in COVER_ART mode, the main image already shows album art)
                if (artworkDisplayMode == ArtworkDisplayMode.CHANNEL_LOGO && trackMetadata!!.albumArtURL != null) {
                    RetryableAsyncImage(
                        model = trackMetadata!!.albumArtURL,
                        contentDescription = "Album art",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = trackMetadata!!.title,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = trackMetadata!!.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.toggleLovedTrack() },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            imageVector = if (isTrackLoved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isTrackLoved) "Unlove track" else "Love track",
                            tint = if (isTrackLoved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (playbackState is PlaybackState.Buffering || playbackState is PlaybackState.CatchingUp) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (playbackState is PlaybackState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { viewModel.togglePlayPause() }) {
                    Text("Retry")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.playPrevious() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(36.dp),
                    )
                }

                FilledIconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = when (playbackState) {
                            is PlaybackState.Playing, is PlaybackState.CatchingUp -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = { viewModel.playNext() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = { viewModel.toggleFavorite() }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.size(36.dp),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                CastButton(modifier = Modifier.size(36.dp))

                IconButton(onClick = { viewModel.stop() }) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            if (isTimeShifted) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { viewModel.seekToLive() }) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

}

/**
 * Now Playing surface for on-demand library (Navidrome) tracks (baw.9.3 / baw.9.5).
 *
 * Deliberately separate from the radio-oriented [NowPlayingSheet] body: there is
 * no channel/EPG/ESPN concept here, playback is source-agnostic play/pause/stop,
 * and next/previous drive the library queue via [NowPlayingViewModel.libraryNext] /
 * [NowPlayingViewModel.libraryPrevious] rather than the radio-only channel methods.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryNowPlayingSheet(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel,
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val track by viewModel.currentLibraryTrack.collectAsStateWithLifecycle()
    val artworkUrl by viewModel.libraryArtworkUrl.collectAsStateWithLifecycle()
    val albumTitle by viewModel.libraryAlbumTitle.collectAsStateWithLifecycle()
    val durationMs by viewModel.libraryDurationMs.collectAsStateWithLifecycle()
    var showUpNext by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentTrack = track ?: return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (artworkUrl != null) {
                RetryableAsyncImage(
                    model = artworkUrl,
                    contentDescription = currentTrack.title,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = currentTrack.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            if (currentTrack.artist != null) {
                Text(
                    text = currentTrack.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (albumTitle != null) {
                Text(
                    text = albumTitle!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (playbackState is PlaybackState.Buffering) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Positional seek bar — library-only (baw.9.5). Radio keeps its
            // existing "LIVE" seek-to-live button and gets no scrubber; a real,
            // seekable duration only exists for on-demand tracks (PlaybackContract).
            if (durationMs != null && durationMs!! > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LibrarySeekBar(
                    viewModel = viewModel,
                    isPlaying = playbackState is PlaybackState.Playing || playbackState is PlaybackState.CatchingUp,
                    durationMs = durationMs!!,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.libraryPrevious() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(36.dp),
                    )
                }

                FilledIconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = when (playbackState) {
                            is PlaybackState.Playing, is PlaybackState.CatchingUp -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = { viewModel.libraryNext() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(36.dp),
                    )
                }

                // Up Next — reorderable queue (baw.9.3). Library-only; radio has no queue.
                IconButton(onClick = { showUpNext = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Up Next",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = { viewModel.stop() }) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showUpNext) {
        UpNextSheet(onDismiss = { showUpNext = false })
    }
}

/**
 * Positional seek bar for library tracks (baw.9.5).
 *
 * Polls [NowPlayingViewModel.currentPositionMs] every 500ms — the same
 * ticker-in-a-`LaunchedEffect` pattern already used for the listening-time
 * clock ([com.adagiostream.android.util.rememberListeningTime]) — rather than
 * threading position through a StateFlow the wrapper would have to poll itself.
 * While the user is dragging, the live poll is suppressed and the slider
 * tracks the drag position; the seek only actually fires on release
 * ([androidx.compose.material3.Slider]'s `onValueChangeFinished`), matching
 * standard scrubber UX and avoiding a flood of seeks mid-drag.
 *
 * SNAP-BACK GUARD (baw.16): libVLC applies a seek asynchronously, so the poll
 * right after release can still read the pre-seek position and the slider
 * visibly jumps backward before catching up. After release, the displayed
 * position is pinned to the seek target until a poll lands within
 * [SEEK_SETTLE_TOLERANCE_MS] of it, or [SEEK_SETTLE_TIMEOUT_MS] elapses
 * (covers a seek that never lands exactly, e.g. keyframe rounding).
 */
@Composable
private fun LibrarySeekBar(
    viewModel: NowPlayingViewModel,
    isPlaying: Boolean,
    durationMs: Long,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }
    var livePositionMs by remember { mutableLongStateOf(viewModel.currentPositionMs()) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var pendingSeekDeadline by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isDragging) {
                val polled = viewModel.currentPositionMs()
                val target = pendingSeekMs
                livePositionMs = when {
                    target == null -> polled
                    kotlin.math.abs(polled - target) <= SEEK_SETTLE_TOLERANCE_MS -> {
                        pendingSeekMs = null
                        polled
                    }
                    System.currentTimeMillis() >= pendingSeekDeadline -> {
                        pendingSeekMs = null
                        polled
                    }
                    else -> target
                }
            }
            delay(500L)
        }
    }

    val displayedPositionMs = if (isDragging) dragPositionMs else livePositionMs
    val sliderValue = (displayedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { fraction ->
                isDragging = true
                dragPositionMs = (fraction * durationMs).toLong()
            },
            onValueChangeFinished = {
                viewModel.seekTo(dragPositionMs)
                livePositionMs = dragPositionMs
                pendingSeekMs = dragPositionMs
                pendingSeekDeadline = System.currentTimeMillis() + SEEK_SETTLE_TIMEOUT_MS
                isDragging = false
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTrackTime(displayedPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTrackTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How close a poll must land to the seek target to count as "settled" (baw.16). */
private const val SEEK_SETTLE_TOLERANCE_MS = 750L

/** Longest we'll pin the display to the seek target before trusting the poll anyway (baw.16). */
private const val SEEK_SETTLE_TIMEOUT_MS = 1_000L

private fun formatTrackTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

/** Isolated Composable so bitrate changes only recompose this text, not the entire sheet. */
@Composable
private fun BitrateDisplay(viewModel: NowPlayingViewModel) {
    val bitrateKbps by viewModel.bitrateKbps.collectAsStateWithLifecycle()
    val bitrateText = BitrateFormatter.format(bitrateKbps)
    if (bitrateText.isNotEmpty()) {
        Text(
            text = bitrateText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
