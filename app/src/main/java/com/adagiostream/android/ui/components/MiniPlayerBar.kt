package com.adagiostream.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.util.rememberElapsedTime

@Composable
fun MiniPlayerBar(
    channel: Channel,
    playbackState: PlaybackState,
    streamStartedAt: Long?,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column {
            if (playbackState is PlaybackState.Buffering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (channel.logoURL != null) {
                    RetryableAsyncImage(
                        model = channel.logoURL,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val elapsed = rememberElapsedTime(streamStartedAt)
                    val statusText = when (playbackState) {
                        is PlaybackState.Buffering -> "Buffering..."
                        is PlaybackState.Playing -> if (elapsed != null) "Playing \u00B7 $elapsed" else "Playing"
                        is PlaybackState.Paused -> if (elapsed != null) "Paused \u00B7 $elapsed" else "Paused"
                        is PlaybackState.Error -> "Error"
                        else -> ""
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = when (playbackState) {
                            is PlaybackState.Playing -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = "Play/Pause",
                    )
                }

                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                    )
                }
            }
        }
    }
}
