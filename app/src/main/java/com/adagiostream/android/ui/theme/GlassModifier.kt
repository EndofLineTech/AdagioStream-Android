package com.adagiostream.android.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Applies a glass morphism background effect.
 * On API 31+ (Android 12), uses blur for a frosted glass look.
 * On older APIs, uses a higher-opacity translucent background.
 */
@Composable
fun Modifier.glassBackground(
    shape: Shape = RoundedCornerShape(0.dp),
    alpha: Float? = null,
): Modifier {
    val surfaceColor = MaterialTheme.colorScheme.surface
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val effectiveAlpha = alpha ?: 0.65f
        this
            .clip(shape)
            .blur(radius = 20.dp)
            .background(surfaceColor.copy(alpha = effectiveAlpha), shape)
    } else {
        val effectiveAlpha = alpha ?: 0.85f
        this
            .clip(shape)
            .background(surfaceColor.copy(alpha = effectiveAlpha), shape)
    }
}
