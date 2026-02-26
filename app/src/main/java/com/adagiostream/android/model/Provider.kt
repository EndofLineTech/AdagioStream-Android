package com.adagiostream.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Provider(
    val id: String,
    val name: String,
    val type: ProviderType,
)

@Serializable
sealed interface ProviderType {

    @Serializable
    @SerialName("m3u")
    data class M3U(
        val url: String,
        val epgUrl: String? = null,
    ) : ProviderType

    @Serializable
    @SerialName("xtream")
    data class XtreamCodes(
        val host: String,
        val username: String,
        val password: String,
    ) : ProviderType
}
