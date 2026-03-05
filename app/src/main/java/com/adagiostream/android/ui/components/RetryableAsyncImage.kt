package com.adagiostream.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import androidx.compose.ui.platform.LocalContext

@Composable
fun RetryableAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var hasError by remember(model) { androidx.compose.runtime.mutableStateOf(false) }
    val context = LocalContext.current

    if (hasError) {
        Box(
            modifier = modifier.clickable {
                hasError = false
                retryKey++
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry loading image",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val imageModel = if (!allowHardware) {
            ImageRequest.Builder(context)
                .data(if (retryKey > 0) "$model#retry=$retryKey" else model)
                .allowHardware(false)
                .build()
        } else {
            if (retryKey > 0) "$model#retry=$retryKey" else model
        }

        AsyncImage(
            model = imageModel,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    hasError = true
                }
                onState?.invoke(state)
            },
        )
    }
}
