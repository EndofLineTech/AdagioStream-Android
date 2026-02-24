package com.adagiostream.android.util

object BitrateFormatter {
    fun format(kbps: Float): String {
        return when {
            kbps <= 0f -> ""
            kbps >= 1000f -> "%.1f Mbps".format(kbps / 1000f)
            else -> "%.0f kbps".format(kbps)
        }
    }
}
