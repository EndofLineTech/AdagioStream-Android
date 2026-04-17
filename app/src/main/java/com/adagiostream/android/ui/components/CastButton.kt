package com.adagiostream.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.adagiostream.android.util.DebugLogger
import com.google.android.gms.cast.framework.CastButtonFactory

private class TintableMediaRouteButton(context: Context) : MediaRouteButton(context) {
    var iconTint: ColorStateList? = null

    private val remoteIndicatorField by lazy {
        try {
            MediaRouteButton::class.java.getDeclaredField("mRemoteIndicator")
                .apply { isAccessible = true }
        } catch (_: Exception) { null }
    }

    override fun onDraw(canvas: Canvas) {
        iconTint?.let { tint ->
            (remoteIndicatorField?.get(this) as? Drawable)?.setTintList(tint)
        }
        super.onDraw(canvas)
    }
}

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val activityContext = LocalContext.current.let { ctx ->
        var c = ctx
        while (c is ContextThemeWrapper && c !is Activity) {
            c = c.baseContext
        }
        c
    }
    val tintColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        factory = { _ ->
            try {
                TintableMediaRouteButton(activityContext).apply {
                    iconTint = ColorStateList.valueOf(tintColor)
                    CastButtonFactory.setUpMediaRouteButton(activityContext, this)
                }
            } catch (e: Exception) {
                DebugLogger.log("CastButton: setup failed — ${e.message}", DebugLogger.Category.PLAYER)
                View(activityContext).apply { visibility = View.GONE }
            }
        },
        update = { view ->
            if (view is TintableMediaRouteButton) {
                view.iconTint = ColorStateList.valueOf(tintColor)
                view.invalidate()
            }
        },
        modifier = modifier,
    )
}
