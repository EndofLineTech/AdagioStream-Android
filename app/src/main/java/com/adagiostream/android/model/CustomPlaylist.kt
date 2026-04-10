package com.adagiostream.android.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomPlaylistEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val streamURL: String,
    val logoURL: String? = null,
    val sourceChannelID: String? = null,
) {
    constructor(channel: Channel) : this(
        name = channel.name,
        streamURL = channel.streamURL,
        logoURL = channel.logoURL,
        sourceChannelID = channel.id,
    )

    fun asChannel(): Channel = Channel(
        id = id,
        name = name,
        streamURL = streamURL,
        logoURL = logoURL,
        group = "Custom",
    )
}

@Serializable
data class CustomPlaylistGroup(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val entries: List<CustomPlaylistEntry> = emptyList(),
)

@Serializable
data class CustomPlaylist(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val groups: List<CustomPlaylistGroup> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
