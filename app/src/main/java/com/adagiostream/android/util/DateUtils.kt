package com.adagiostream.android.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DateUtils {

    private val xmltvFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val displayTimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun parseXmltvDate(dateStr: String): Long {
        return try {
            xmltvFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun formatTime(millis: Long): String {
        return displayTimeFormat.format(millis)
    }
}
