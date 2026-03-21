package com.adagiostream.android.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap

object EdgeColorSampler {

    private val cache = ConcurrentHashMap<String, Color>()
    private val fallback = Color(0xFF303030)

    fun getColor(url: String?, bitmap: Bitmap?): Color {
        if (url == null || bitmap == null) return fallback
        cache[url]?.let { return it }

        val color = sampleEdges(bitmap)
        cache[url] = color
        return color
    }

    private fun sampleEdges(bitmap: Bitmap): Color {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return fallback

        val stride = maxOf(w, h) / 20
        if (stride <= 0) return fallback

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        fun sample(x: Int, y: Int) {
            if (x < 0 || x >= w || y < 0 || y >= h) return
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel shr 24) and 0xFF
            if (alpha < 128) return
            rSum += (pixel shr 16) and 0xFF
            gSum += (pixel shr 8) and 0xFF
            bSum += pixel and 0xFF
            count++
        }

        // Top edge
        var x = 0
        while (x < w) { sample(x, 0); x += stride }
        // Bottom edge
        x = 0
        while (x < w) { sample(x, h - 1); x += stride }
        // Left edge
        var y = 0
        while (y < h) { sample(0, y); y += stride }
        // Right edge
        y = 0
        while (y < h) { sample(w - 1, y); y += stride }

        if (count == 0) return fallback

        return Color(
            red = (rSum / count).toInt(),
            green = (gSum / count).toInt(),
            blue = (bSum / count).toInt(),
        )
    }
}
