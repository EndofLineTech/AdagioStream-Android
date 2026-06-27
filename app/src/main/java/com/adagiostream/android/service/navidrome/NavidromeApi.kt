package com.adagiostream.android.service.navidrome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Navidrome / Subsonic REST API client — fetch/envelope/error/ping foundation.
 *
 * Port of Services/NavidromeAPI.swift core (the ping + envelope decode layer).
 * Browse endpoints (getArtists, getAlbum, etc.) are E2 scope.
 *
 * Constructed with an injected [OkHttpClient] + host + username + password so
 * it is unit-testable without Hilt. In production, the app's [NetworkModule]
 * OkHttpClient is passed in (which has no logging interceptor in release).
 *
 * All public methods are `suspend` and run on [Dispatchers.IO].
 *
 * Explicit timeouts are set at construction when none are provided on the
 * injected client — OkHttp defaults are effectively unbounded.
 *
 * @param client  OkHttpClient to use for all requests. Timeouts should be set
 *   on this client (or they fall back to the [defaultClient] defaults: 10s
 *   connect, 30s read).
 * @param host    Base URL of the Navidrome server, e.g. `"https://music.example.com"`.
 * @param username  Subsonic username.
 * @param password  Subsonic password. Never logged or included in [toString].
 */
class NavidromeApi(
    private val client: OkHttpClient,
    private val host: String,
    private val username: String,
    private val password: String,
) {
    private val auth = SubsonicAuth(username = username, password = password)

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Checks connectivity and basic auth by calling `ping.view`.
     *
     * Returns normally when the server responds with `status == "ok"`.
     * Throws a [NavidromeApiException] on any failure.
     */
    suspend fun ping() {
        val url = buildUrl(endpoint = "ping", params = emptyMap())
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/ping.view")

        val data = fetchRawData(url)
        val envelope = decodeEnvelope(data)
        checkStatus(envelope)
    }

    // -------------------------------------------------------------------------
    // Private: URL building
    // -------------------------------------------------------------------------

    /**
     * Builds a fully-qualified Subsonic REST URL with auth params.
     *
     * @return null if the host string is not a valid HTTP/HTTPS URL.
     */
    private fun buildUrl(endpoint: String, params: Map<String, String>): HttpUrl? {
        val base = host.trimEnd('/').toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
            .encodedPath("/rest/$endpoint.view")

        // Append auth params
        for ((key, value) in auth.queryParams()) {
            builder.addQueryParameter(key, value)
        }

        // Append endpoint-specific params
        for ((key, value) in params) {
            builder.addQueryParameter(key, value)
        }

        return builder.build()
    }

    // -------------------------------------------------------------------------
    // Private: HTTP execution
    // -------------------------------------------------------------------------

    /**
     * Executes the HTTP GET, maps network errors to [NavidromeApiException],
     * and returns raw response bytes.
     */
    private suspend fun fetchRawData(url: HttpUrl): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw NavidromeApiException.TimedOut
        } catch (e: IOException) {
            throw NavidromeApiException.Unreachable(e)
        }

        response.use { resp ->
            val statusCode = resp.code
            if (statusCode !in 200..299) {
                throw NavidromeApiException.ServerError(statusCode)
            }
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    // -------------------------------------------------------------------------
    // Private: response decoding
    // -------------------------------------------------------------------------

    /** Decodes the Subsonic status envelope. Throws [NavidromeApiException.NotSubsonicServer] on decode failure. */
    private fun decodeEnvelope(data: ByteArray): SubsonicStatusEnvelope {
        return try {
            json.decodeFromString<SubsonicStatusEnvelope>(String(data, Charsets.UTF_8))
        } catch (e: Exception) {
            throw NavidromeApiException.NotSubsonicServer
        }
    }

    /**
     * Inspects the decoded envelope status and throws the appropriate
     * [NavidromeApiException] if status != "ok".
     */
    private fun checkStatus(envelope: SubsonicStatusEnvelope) {
        if (envelope.status != "ok") {
            val code = envelope.error?.code ?: 0
            val message = envelope.error?.message ?: "Unknown error"
            // Subsonic codes 40 / 41 are authentication failures.
            if (code == 40 || code == 41) {
                throw NavidromeApiException.AuthFailed
            }
            throw NavidromeApiException.SubsonicError(code = code, message = message)
        }
    }

    // -------------------------------------------------------------------------
    // toString: password excluded
    // -------------------------------------------------------------------------

    /** Password intentionally excluded to prevent accidental log exposure. */
    override fun toString(): String = "NavidromeApi(host=$host, username=$username)"

    companion object {
        /**
         * Creates an [OkHttpClient] with explicit timeouts for use when no
         * client is injected.  Not used in production (the [NetworkModule] client
         * is always injected); available for convenience in manual tests.
         *
         * Note: The production [NetworkModule] client must NOT have a logging
         * interceptor in release builds — no logging of request URLs which
         * would expose the Subsonic auth params.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
