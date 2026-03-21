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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.ArtworkDisplayMode
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.ui.components.RetryableAsyncImage
import com.adagiostream.android.util.BitrateFormatter
import com.adagiostream.android.util.rememberListeningTime

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
                Text(
                    text = espnGame!!.nowPlayingSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
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
