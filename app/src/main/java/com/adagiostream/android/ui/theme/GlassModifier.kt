package com.adagiostream.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Applies a translucent surface background.
 */
@Composable
fun Modifier.glassBackground(
    shape: Shape = RoundedCornerShape(0.dp),
    alpha: Float? = null,
): Modifier {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val effectiveAlpha = alpha ?: 0.85f
    return this
        .clip(shape)
        .background(surfaceColor.copy(alpha = effectiveAlpha), shape)
}
