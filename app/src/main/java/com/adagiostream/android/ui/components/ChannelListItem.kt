package com.adagiostream.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.TrackMetadata

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelListItem(
    channel: Channel,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trackMetadata: TrackMetadata? = null,
    espnGame: ESPNGameInfo? = null,
    currentProgram: String? = null,
    onLongClick: (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
        }

        if (channel.logoURL != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF303030)),
                contentAlignment = Alignment.Center,
            ) {
                RetryableAsyncImage(
                    model = channel.logoURL,
                    contentDescription = channel.name,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Radio,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            val subtitleText = when {
                trackMetadata != null -> "${trackMetadata.artist} \u2013 ${trackMetadata.title}"
                espnGame != null -> espnGame.displayText
                currentProgram != null -> currentProgram
                else -> channel.group
            }
            val subtitleColor = if (espnGame != null && espnGame.state == ESPNGameInfo.GameState.LIVE && trackMetadata == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onFavoriteToggle) {
            Icon(
                imageVector = if (channel.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (channel.isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (channel.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
