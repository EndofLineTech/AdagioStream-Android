package com.adagiostream.android.ui.screens.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.ui.components.EPGInfoCard
import com.adagiostream.android.ui.components.RetryableAsyncImage
import com.adagiostream.android.ui.screens.epg.EPGBottomSheet
import com.adagiostream.android.util.BitrateFormatter
import com.adagiostream.android.util.rememberElapsedTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val bitrateKbps by viewModel.bitrateKbps.collectAsStateWithLifecycle()
    val streamStartedAt by viewModel.streamStartedAt.collectAsStateWithLifecycle()
    val epgEntries by viewModel.currentEPGEntries.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEPGSheet by remember { mutableStateOf(false) }
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
            if (channel.logoURL != null) {
                RetryableAsyncImage(
                    model = channel.logoURL,
                    contentDescription = channel.name,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
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

            val elapsed = rememberElapsedTime(streamStartedAt)
            val statusText = when (playbackState) {
                is PlaybackState.Buffering -> "Buffering..."
                is PlaybackState.Playing -> if (elapsed != null) "Playing \u00B7 $elapsed" else "Playing"
                is PlaybackState.Paused -> if (elapsed != null) "Paused \u00B7 $elapsed" else "Paused"
                is PlaybackState.Error -> (playbackState as PlaybackState.Error).message
                else -> ""
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val bitrateText = BitrateFormatter.format(bitrateKbps)
            if (bitrateText.isNotEmpty()) {
                Text(
                    text = bitrateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (playbackState is PlaybackState.Buffering) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (epgEntries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                EPGInfoCard(entries = epgEntries)
                TextButton(onClick = { showEPGSheet = true }) {
                    Text("View Full Program Guide")
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
                            is PlaybackState.Playing -> Icons.Default.Pause
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

    if (showEPGSheet) {
        EPGBottomSheet(
            entries = epgEntries,
            channelName = channel.name,
            onDismiss = { showEPGSheet = false },
        )
    }
}
