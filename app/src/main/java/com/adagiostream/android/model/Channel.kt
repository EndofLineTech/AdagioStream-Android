package com.adagiostream.android.model

import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val streamURL: String,
    val logoURL: String? = null,
    val group: String,
    val epgChannelID: String? = null,
    val isFavorite: Boolean = false,
    val xtreamStreamId: Long? = null,
)
