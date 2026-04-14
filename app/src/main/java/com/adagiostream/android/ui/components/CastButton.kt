package com.adagiostream.android.ui.components

import android.view.ContextThemeWrapper
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
                // MediaRouteButton requires a theme with an opaque window background.
                // Compose contexts may have translucent backgrounds, so wrap with
                // AppCompat theme which provides the required attributes.
                val themedContext = ContextThemeWrapper(
                    context,
                    androidx.appcompat.R.style.Theme_AppCompat_DayNight,
                )
                MediaRouteButton(themedContext).apply {
                    CastButtonFactory.setUpMediaRouteButton(themedContext, this)
                }
            } catch (e: Exception) {
                DebugLogger.log("CastButton: setup failed — ${e.message}", DebugLogger.Category.PLAYER)
                View(context).apply { visibility = View.GONE }
            }
        },
        modifier = modifier,
    )
}
