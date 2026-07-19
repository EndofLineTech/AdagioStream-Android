package com.adagiostream.android.ui.screens.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.EpisodeProgressState
import com.adagiostream.android.service.audiobookshelf.formatPubDate
import com.adagiostream.android.ui.screens.audiobooks.formatBookDuration
import com.adagiostream.android.ui.screens.music.DownloadUiState
import com.adagiostream.android.ui.screens.music.TrackDownloadButton

/**
 * One podcast episode list row (beads_adagio-59p.2.1) — port of iOS
 * `PodcastEpisodeRowView`: unplayed dot, title, optional show title, pubDate
 * · duration line, then a progress bar (started) or a Played badge
 * (finished), all driven by HYDRATED progress.
 *
 * @param progressState the hydrated state, or `null` while not yet hydrated
 *   (renders no dot/bar/badge — avoids an unplayed-dot flash before the
 *   fetch lands).
 */
@Composable
fun PodcastEpisodeRow(
    episode: AbsEpisode,
    progressState: EpisodeProgressState?,
    showTitle: String? = null,
    downloadState: DownloadUiState = DownloadUiState.NOT_DOWNLOADED,
    onDownloadTap: () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(
                    color = if (progressState == EpisodeProgressState.Unplayed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title ?: "Episode",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            showTitle?.takeIf { it.isNotBlank() }?.let { show ->
                Text(
                    text = show,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val dateText = formatPubDate(episode.pubDate)
            val durationText = episode.duration?.let { formatBookDuration(it) }
            val metaLine = listOfNotNull(dateText, durationText).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (progressState) {
                is EpisodeProgressState.InProgress -> LinearProgressIndicator(
                    progress = { progressState.fraction.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )

                EpisodeProgressState.Finished -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Played",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Unplayed (dot only) or not-yet-hydrated: nothing extra.
                else -> Unit
            }
        }

        // Trailing download affordance (bead .2.3) — reuses the music
        // TrackDownloadButton state machine (download / spinner / delete).
        TrackDownloadButton(state = downloadState, onTap = onDownloadTap)
    }
}
