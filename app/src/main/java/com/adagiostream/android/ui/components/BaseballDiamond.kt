package com.adagiostream.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small baseball diamond showing base runners.
 * Bases are drawn as rotated squares (diamonds); filled when occupied.
 */
@Composable
fun BaseballDiamond(
    onFirst: Boolean,
    onSecond: Boolean,
    onThird: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val baseSize = w * 0.28f
        val strokeWidth = w * 0.06f

        // Base positions: 1B = right, 2B = top, 3B = left
        // Home plate is implied at the bottom center
        val first = Offset(w * 0.78f, h * 0.50f)
        val second = Offset(w * 0.50f, h * 0.18f)
        val third = Offset(w * 0.22f, h * 0.50f)
        val home = Offset(w * 0.50f, h * 0.82f)

        // Draw base paths
        val pathColor = inactiveColor
        drawLine(pathColor, home, first, strokeWidth)
        drawLine(pathColor, first, second, strokeWidth)
        drawLine(pathColor, second, third, strokeWidth)
        drawLine(pathColor, third, home, strokeWidth)

        // Draw bases
        drawBase(first, baseSize, onFirst, activeColor, inactiveColor, strokeWidth)
        drawBase(second, baseSize, onSecond, activeColor, inactiveColor, strokeWidth)
        drawBase(third, baseSize, onThird, activeColor, inactiveColor, strokeWidth)
    }
}

@Preview(showBackground = true, name = "Baseball Diamond - All Situations")
@Composable
private fun BaseballDiamondPreview() {
    MaterialTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val situations = listOf(
                    Triple(false, false, false) to "Bases empty",
                    Triple(true, false, false) to "Runner on 1st",
                    Triple(false, true, false) to "Runner on 2nd",
                    Triple(false, false, true) to "Runner on 3rd",
                    Triple(true, true, false) to "1st & 2nd",
                    Triple(true, false, true) to "1st & 3rd",
                    Triple(false, true, true) to "2nd & 3rd",
                    Triple(true, true, true) to "Bases loaded",
                )
                for ((bases, label) in situations) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BaseballDiamond(
                            onFirst = bases.first,
                            onSecond = bases.second,
                            onThird = bases.third,
                            size = 32.dp,
                        )
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawBase(
    center: Offset,
    size: Float,
    occupied: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    strokeWidth: Float,
) {
    rotate(45f, pivot = center) {
        val topLeft = Offset(center.x - size / 2, center.y - size / 2)
        if (occupied) {
            drawRect(activeColor, topLeft, Size(size, size))
        } else {
            drawRect(inactiveColor, topLeft, Size(size, size), style = Stroke(strokeWidth))
        }
    }
}
