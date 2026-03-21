package com.adagiostream.android.model

import kotlinx.serialization.Serializable

@Serializable
data class TrackMetadata(
    val artist: String,
    val title: String,
    val album: String? = null,
    val albumArtURL: String? = null,
    val timestamp: Long = 0L,
)
