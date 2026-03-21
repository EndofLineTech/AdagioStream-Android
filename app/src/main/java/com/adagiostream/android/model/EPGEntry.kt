package com.adagiostream.android.model

import kotlinx.serialization.Serializable

@Serializable
data class EPGEntry(
    val channelID: String,
    val title: String,
    val description: String? = null,
    val start: Long,
    val end: Long,
) {
    val isCurrentlyAiring: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now in start until end
        }

    val isUpcoming: Boolean
        get() = System.currentTimeMillis() < start
}
