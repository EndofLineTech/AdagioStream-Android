package com.adagiostream.android.service.audiobookshelf

import com.adagiostream.android.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Audiobookshelf JWT auth: login, refresh with token ROTATION, and a
 * 401 → refresh → retry wrapper. Port of iOS `AudiobookshelfAuth` (JWT-only,
 * min server 2.26.0; no legacy static-token fallback).
 *
 * The refresh token is rotated on EVERY refresh — the new pair is pushed to
 * [onTokensChanged] immediately so the caller can persist it to the encrypted
 * accounts store. `onTokensChanged(null)` means the tokens were cleared
 * (refresh failed → re-auth required).
 *
 * Concurrent 401-driven refreshes are coalesced through [refreshMutex]: the
 * first caller refreshes; callers that were waiting see the access token has
 * already changed from their stale one and skip straight to retry. Without
 * this, a second refresh would POST the already-rotated (now-invalid) refresh
 * token, get rejected, and clear the freshly-rotated pair.
 *
 * Tokens are secrets: they are never logged and are excluded from [toString].
 *
 * @param username null for future SSO accounts (no password login possible).
 * @param password null/blank means login is impossible — a 401 with no valid
 *   refresh token surfaces [AudiobookshelfApiException.ReauthRequired].
 */
class AudiobookshelfAuth(
    private val client: OkHttpClient,
    private val host: String,
    private val username: String?,
    private val password: String?,
    initialTokens: Tokens? = null,
    private val onTokensChanged: (Tokens?) -> Unit = {},
) {
    /** An access + refresh token pair. Never log either value. */
    data class Tokens(val accessToken: String, val refreshToken: String) {
        /** Tokens intentionally excluded to prevent accidental log exposure. */
        override fun toString(): String = "Tokens(****)"
    }

    /** Non-token info captured from the login payload for later branches. */
    data class Session(
        val tokens: Tokens,
        val canDownload: Boolean,
        val mediaProgress: List<AbsMediaProgress>,
    )

    @Volatile
    private var tokens: Tokens? = initialTokens

    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Current access token for token-in-query URLs (cover art, streaming). Null until first login. */
    fun currentAccessToken(): String? = tokens?.accessToken

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /**
     * `POST /login` with `x-return-tokens: true` so the refresh token comes in
     * the body. Persists the returned token pair (via [onTokensChanged]) and
     * returns the session.
     *
     * SSO accounts have no password — never POST empty creds (that returns a
     * misleading 401); surface [AudiobookshelfApiException.ReauthRequired] so
     * the UI routes back to the SSO flow (later branch).
     */
    suspend fun login(): Session = withContext(Dispatchers.IO) {
        val user = username
        val pass = password
        if (user.isNullOrBlank() || pass.isNullOrEmpty()) {
            throw AudiobookshelfApiException.ReauthRequired
        }
        val url = AudiobookshelfUrl.resolve(host, "/login")
            ?: throw AudiobookshelfApiException.InvalidUrl(host)

        val body = json.encodeToString(LoginRequest(username = user, password = pass))
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("x-return-tokens", "true")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        perform(request).use { response ->
            if (response.code !in 200..299) {
                throw AudiobookshelfApiException.LoginFailed(response.code)
            }
            val payload = try {
                json.decodeFromString<AbsLoginResponse>(response.body.string())
            } catch (e: Exception) {
                throw AudiobookshelfApiException.DecodingError(e)
            }
            val access = payload.user?.accessToken
            val refresh = payload.refreshToken ?: payload.user?.refreshToken
            if (access == null || refresh == null) {
                throw AudiobookshelfApiException.DecodingError(
                    IllegalStateException("Login response missing token pair"),
                )
            }
            val pair = Tokens(accessToken = access, refreshToken = refresh)
            persist(pair)
            Session(
                tokens = pair,
                canDownload = payload.user.permissions?.download ?: false,
                mediaProgress = payload.user.mediaProgress,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Authenticated execution (401 → refresh → retry)
    // -------------------------------------------------------------------------

    /**
     * Executes [request] with `Authorization: Bearer` injected. On a 401 it
     * refreshes once (rotating the refresh token, coalesced across concurrent
     * callers), retries with the new token, and returns the retried response.
     * If no tokens are present it logs in first.
     *
     * The caller owns closing the returned [Response].
     */
    suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        if (tokens == null) {
            // Coalesce concurrent first-time logins the same way 401-driven
            // refreshes are — N tokenless callers must produce ONE POST /login.
            refreshMutex.withLock {
                if (tokens == null) login()
            }
        }
        val access = tokens?.accessToken ?: throw AudiobookshelfApiException.ReauthRequired

        val response = perform(withBearer(request, access))
        if (response.code != 401) return@withContext response
        response.close()

        refreshIfNeeded(staleAccessToken = access)
        val newAccess = tokens?.accessToken ?: throw AudiobookshelfApiException.ReauthRequired
        perform(withBearer(request, newAccess))
    }

    // -------------------------------------------------------------------------
    // Refresh (rotates the refresh token)
    // -------------------------------------------------------------------------

    /**
     * Refreshes at most once for a wave of concurrent 401s. If someone already
     * rotated the pair since this caller's 401 (its access token changed), the
     * caller skips the refresh and just retries with the new token.
     */
    private suspend fun refreshIfNeeded(staleAccessToken: String) {
        refreshMutex.withLock {
            val current = tokens?.accessToken
            if (current != null && current != staleAccessToken) return // already rotated
            refresh()
        }
    }

    /**
     * `POST /auth/refresh` with `x-refresh-token`. Persists the ROTATED pair on
     * success.
     *
     * Failure policy: tokens are cleared (→ [AudiobookshelfApiException.ReauthRequired])
     * ONLY on a definitive 4xx rejection from the server. Transport errors
     * (timeout/unreachable), 5xx, and malformed 2xx bodies KEEP the stored pair
     * and surface the matching typed error — a network blip must not force a
     * re-login. If the server really rotated and this response was lost, the
     * next refresh 401s and clears cleanly.
     */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        val current = tokens?.refreshToken ?: run {
            forgetTokens()
            throw AudiobookshelfApiException.ReauthRequired
        }
        val url = AudiobookshelfUrl.resolve(host, "/auth/refresh")
            ?: throw AudiobookshelfApiException.InvalidUrl(host)

        val request = Request.Builder()
            .url(url)
            .header("x-refresh-token", current)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        // TimedOut/Unreachable from perform() propagate WITHOUT clearing tokens.
        val response = perform(request)
        response.use { resp ->
            when {
                resp.code in 400..499 -> {
                    // Definitive rejection — the pair is dead.
                    forgetTokens()
                    throw AudiobookshelfApiException.ReauthRequired
                }
                resp.code !in 200..299 ->
                    throw AudiobookshelfApiException.ServerError(resp.code)
            }
            val payload = try {
                json.decodeFromString<AbsRefreshResponse>(resp.body.string())
            } catch (e: Exception) {
                throw AudiobookshelfApiException.DecodingError(e)
            }
            val access = payload.user?.accessToken ?: payload.accessToken
            val newRefresh = payload.refreshToken ?: payload.user?.refreshToken
            if (access == null || newRefresh == null) {
                throw AudiobookshelfApiException.DecodingError(
                    IllegalStateException("Refresh response missing token pair"),
                )
            }
            persist(Tokens(accessToken = access, refreshToken = newRefresh))
        }
    }

    // -------------------------------------------------------------------------
    // Token persistence
    // -------------------------------------------------------------------------

    private fun persist(pair: Tokens) {
        tokens = pair
        notifyTokensChanged(pair)
    }

    private fun forgetTokens() {
        tokens = null
        notifyTokensChanged(null)
    }

    /**
     * A failing persistence callback must not fail the request that triggered
     * the rotation — the in-memory tokens stay valid and the session keeps
     * working.
     */
    private fun notifyTokensChanged(pair: Tokens?) {
        try {
            onTokensChanged(pair)
        } catch (e: Exception) {
            // ponytail: swallow + log — the next rotation re-attempts the write;
            // worst case a stale stored refresh token forces one re-login on the
            // next launch. Add a write retry only if that shows up in the field.
            // Never log token contents.
            DebugLogger.log(
                "Audiobookshelf token persistence failed: ${e.javaClass.simpleName}: ${e.message}",
                DebugLogger.Category.GENERAL,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun withBearer(request: Request, accessToken: String): Request =
        request.newBuilder().header("Authorization", "Bearer $accessToken").build()

    /** Executes the call, mapping transport errors to typed exceptions. */
    private fun perform(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: SocketTimeoutException) {
        throw AudiobookshelfApiException.TimedOut
    } catch (e: IOException) {
        throw AudiobookshelfApiException.Unreachable(e)
    }

    /** Credentials and tokens intentionally excluded to prevent log exposure. */
    override fun toString(): String = "AudiobookshelfAuth(host=$host)"

    @kotlinx.serialization.Serializable
    private data class LoginRequest(val username: String, val password: String)

    companion object {
        internal val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
