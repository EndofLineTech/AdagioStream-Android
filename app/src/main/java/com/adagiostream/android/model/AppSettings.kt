package com.adagiostream.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val setupCompleted: Boolean = false,
    val bufferDurationSeconds: Int = 10,
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val textSizeMode: TextSizeMode = TextSizeMode.M,
    val sortMode: SortMode = SortMode.ALPHABETICAL,
    val groupSortMode: SortMode = SortMode.ALPHABETICAL,
    val sortPrefixes: List<String> = listOf("Radio: ", "TV: "),
    val debugLoggingEnabled: Boolean = false,
    val startupStreamID: String? = null,
    val enabledGroups: Set<String>? = null,
    val favoriteGroupOrder: List<String> = emptyList(),
    val artworkDisplayMode: ArtworkDisplayMode = ArtworkDisplayMode.COVER_ART,
    val espnPollingIntervalSeconds: Int = 15,
    val channelGroupingMode: ChannelGroupingMode = ChannelGroupingMode.ALL_GROUPS,
    /**
     * Alpha feature gate for the Music tab (baw.2.6).
     *
     * When `false` (the default), the Music tab is hidden from the bottom
     * navigation bar and the Navidrome library browse UI is inaccessible.
     * Set to `true` in Settings → Developer to expose the tab for testing
     * without shipping an incomplete feature to all users.
     *
     * Matches the iOS gate-flip pattern used to promote E1→E3 incrementally.
     */
    val musicTabEnabled: Boolean = false,
    /**
     * Android Auto browse-root ordering when a Subsonic account is configured
     * (baw.7.1, iOS parity: `CarPlaySourceOrder`). Only surfaced in Settings
     * when a Subsonic account exists.
     */
    val autoSourceOrder: AutoSourceOrder = AutoSourceOrder.STREAMING_FIRST,
)

@Serializable
enum class ArtworkDisplayMode(val displayName: String) {
    COVER_ART("Cover Art"),
    CHANNEL_LOGO("Channel Logo"),
}

@Serializable
enum class SortMode(val displayName: String) {
    @SerialName("PROVIDER_ORDER")
    ACCOUNT_ORDER("Account Order"),
    NATURAL("Natural"),
    ALPHABETICAL("A-Z"),
}

@Serializable
enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class ChannelGroupingMode(val displayName: String) {
    ALL_GROUPS("All Groups"),
    BY_PROVIDER("By Provider"),
    BY_SOURCE("By Source"),
}

/** Android Auto browse-root section order (baw.7.1, iOS parity: `CarPlaySourceOrder`). */
@Serializable
enum class AutoSourceOrder(val displayName: String) {
    STREAMING_FIRST("Streaming First"),
    MUSIC_FIRST("Music First"),
}

@Serializable
enum class TextSizeMode(val scaleFactor: Float, val displayName: String) {
    XS(0.8f, "Extra Small"),
    S(0.9f, "Small"),
    M(1.0f, "Medium"),
    L(1.1f, "Large"),
    XL(1.2f, "Extra Large"),
    XXL(1.25f, "XXL"),
    XXXL(1.3f, "XXXL"),
    ACCESSIBILITY_1(1.4f, "Accessibility 1"),
    ACCESSIBILITY_2(1.6f, "Accessibility 2"),
    ACCESSIBILITY_3(1.8f, "Accessibility 3"),
}
