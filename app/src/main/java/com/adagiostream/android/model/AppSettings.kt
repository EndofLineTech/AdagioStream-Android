package com.adagiostream.android.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val bufferDurationSeconds: Int = 10,
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
)

@Serializable
enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK,
}
