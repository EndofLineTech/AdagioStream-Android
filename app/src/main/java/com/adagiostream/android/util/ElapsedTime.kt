package com.adagiostream.android.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Ticks every second and returns a formatted elapsed time string, or null when [startedAt] is null. */
@Composable
fun rememberElapsedTime(startedAt: Long?): String? {
    if (startedAt == null) return null

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val elapsed = ((now - startedAt) / 1000L).coerceAtLeast(0)
    return formatElapsed(elapsed)
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
