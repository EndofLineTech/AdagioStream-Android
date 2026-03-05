package com.adagiostream.android.model

import kotlinx.serialization.Serializable

@Serializable
data class LovedTrack(
    val artist: String,
    val title: String,
    val album: String? = null,
    val albumArtURL: String? = null,
    val channelName: String,
    val lovedAt: Long = System.currentTimeMillis(),
)
