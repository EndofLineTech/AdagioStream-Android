package com.adagiostream.android.ui.screens.music

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The per-track download button (baw.6.2) — a port of iOS TrackDownloadButton.swift.
 *
 * Five visual states, each with a tap action handled by [DownloadsViewModel.onButtonTap]:
 *  - not-downloaded → download arrow → tap downloads
 *  - queued → spinner → tap cancels
 *  - downloading → spinner → tap cancels
 *  - completed → check circle → tap deletes
 *  - failed → error icon → tap retries
 */
@Composable
fun TrackDownloadButton(
    state: DownloadUiState,
    onTap: () -> Unit,
) {
    IconButton(onClick = onTap, modifier = Modifier.size(36.dp)) {
        when (state) {
            DownloadUiState.NOT_DOWNLOADED -> Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )

            DownloadUiState.QUEUED, DownloadUiState.DOWNLOADING -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )

            DownloadUiState.COMPLETED -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Downloaded — tap to delete",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            DownloadUiState.FAILED -> Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Download failed — tap to retry",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The cancel affordance shown next to an in-flight download (the spinner is the
 * primary control; this provides an explicit "x" where layout allows).
 */
@Composable
fun TrackDownloadCancelIcon() {
    Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = "Cancel download",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
    )
}
