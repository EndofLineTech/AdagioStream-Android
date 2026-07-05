package com.adagiostream.android.model

import com.adagiostream.android.service.player.RepeatMode
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
     * Persisted library-playback shuffle/repeat state (baw.9.4).
     *
     * [MusicQueueManager][com.adagiostream.android.service.player.MusicQueueManager]
     * previously held these only in memory, so they reset every app restart.
     * Restored by [MusicPlaybackCoordinator][com.adagiostream.android.service.player.MusicPlaybackCoordinator]
     * on construction and saved whenever the user changes either. Radio
     * playback is unaffected — it never reads these fields.
     */
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /**
     * Offline mode (baw.12) — mirrors the iOS `AppSettings.offlineMode` toggle.
     *
     * When `true`, the Music tab shows only downloaded tracks and fires no
     * network browse/search requests. Radio/live tabs are unaffected.
     */
    val offlineMode: Boolean = false,
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
