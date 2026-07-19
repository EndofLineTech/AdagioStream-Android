package com.adagiostream.android.ui.screens.audiobooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfCoverArtRequest

/**
 * A square audiobook cover thumbnail (beads_adagio-59p.1.4).
 *
 * Loads via [AudiobookshelfCoverArtRequest] so the Coil cache key is stable
 * (host + item id + width) while the tokened URL is built fresh at fetch time
 * — same pattern as Navidrome cover art. Falls back to a book icon when no
 * API is available.
 */
@Composable
fun AudiobookCover(
    api: AudiobookshelfApi?,
    itemId: String,
    size: Dp,
    coverWidth: Int,
    modifier: Modifier = Modifier,
) {
    if (api != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(AudiobookshelfCoverArtRequest(api = api, itemId = itemId, width = coverWidth))
                .build(),
            contentDescription = null,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
