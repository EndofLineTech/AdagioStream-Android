package com.adagiostream.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val isEnabled: Boolean = true,
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
        val stripStreamIDs: Boolean = false,
    ) : AccountType

    /**
     * A Navidrome / Subsonic music server account.
     *
     * Credentials ([password]) persist ONLY through the existing AES-256-GCM
     * encrypted accounts store (PersistenceService / Android Keystore) — never
     * in plaintext, never in Room/JSON library cache, never logged.
     *
     * Music accounts produce no IPTV channels; the [AccountManager.loadAllChannels]
     * exhaustive `when` branch for Subsonic is a no-op.
     */
    @Serializable
    @SerialName("subsonic")
    data class Subsonic(
        val host: String,
        val username: String,
        val password: String,
    ) : AccountType

    /**
     * An Audiobookshelf server account (JWT auth, min server 2.26.0).
     *
     * [accessToken]/[refreshToken] are the rotating JWT pair from login; the
     * refresh token ROTATES on every refresh and the stored pair must be
     * replaced immediately. Like [Subsonic] credentials, the tokens persist
     * ONLY through the AES-256-GCM encrypted accounts store
     * (PersistenceService / Android Keystore) — never in plaintext, never in
     * Room/JSON caches, never logged.
     *
     * [username] is nullable for future SSO/OIDC accounts (no password login).
     *
     * Audiobook accounts produce no IPTV channels; the
     * [AccountManager.loadAllChannels] `when` branch is a no-op like Subsonic.
     */
    @Serializable
    @SerialName("audiobookshelf")
    data class Audiobookshelf(
        val host: String,
        val username: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
    ) : AccountType
}
