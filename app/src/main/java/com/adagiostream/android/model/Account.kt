package com.adagiostream.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
)

@Serializable
sealed interface AccountType {

    @Serializable
    @SerialName("m3u")
    data class M3U(
        val url: String,
        val epgUrl: String? = null,
    ) : AccountType

    @Serializable
    @SerialName("xtream")
    data class XtreamCodes(
        val host: String,
        val username: String,
        val password: String,
    ) : AccountType
}
