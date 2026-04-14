package com.adagiostream.android.ui.components

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.adagiostream.android.util.DebugLogger
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    // Use the Activity context so the Cast dialog inherits the AppCompat theme
    val activityContext = LocalContext.current.let { ctx ->
        var c = ctx
        while (c is ContextThemeWrapper && c !is Activity) {
            c = c.baseContext
        }
        c
    }
    AndroidView(
        factory = { _ ->
            try {
                MediaRouteButton(activityContext).apply {
                    CastButtonFactory.setUpMediaRouteButton(activityContext, this)
                }
            } catch (e: Exception) {
                DebugLogger.log("CastButton: setup failed — ${e.message}", DebugLogger.Category.PLAYER)
                View(activityContext).apply { visibility = View.GONE }
            }
        },
        modifier = modifier,
    )
}
