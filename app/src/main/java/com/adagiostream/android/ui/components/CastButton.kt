package com.adagiostream.android.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.adagiostream.android.util.DebugLogger
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            try {
                MediaRouteButton(context).apply {
                    CastButtonFactory.setUpMediaRouteButton(context, this)
                }
            } catch (e: Exception) {
                DebugLogger.log("CastButton: setup failed — ${e.message}", DebugLogger.Category.PLAYER)
                // Return an invisible empty view if Cast is unavailable
                View(context).apply { visibility = View.GONE }
            }
        },
        modifier = modifier,
    )
}
