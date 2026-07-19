package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Resolves an Audiobookshelf server-relative path against a host, preserving
 * any reverse-proxy subpath in the host URL (e.g. `https://h/audiobookshelf`
 * + `/api/libraries` → `https://h/audiobookshelf/api/libraries`). One
 * implementation for API, login/refresh, cover, and stream URLs — port of iOS
 * `AudiobookshelfURL.resolve`.
 */
internal object AudiobookshelfUrl {
    fun resolve(host: String, path: String, params: Map<String, String> = emptyMap()): HttpUrl? {
        val base = host.trimEnd('/').toHttpUrlOrNull() ?: return null
        val basePath = base.encodedPath.trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        val builder = base.newBuilder().encodedPath(basePath + suffix)
        for ((key, value) in params) {
            builder.addQueryParameter(key, value)
        }
        return builder.build()
    }
}

/**
 * Audiobookshelf REST client — port of iOS `AudiobookshelfAPI.swift`, shaped
 * like [com.adagiostream.android.service.navidrome.NavidromeApi].
 *
 * Auth is JWT via [AudiobookshelfAuth] (Bearer header, 401 → refresh → retry)
 * rather than per-request query params. Streaming and cover-art URLs (where
 * headers can't be set) take the token as a `?token=` query param — those
 * URLs must NEVER be logged.
 *
 * `contentUrl` and cover paths from the server are SERVER-RELATIVE — every
 * URL builder here resolves against the host so reverse-proxy subpaths work.
 *
 * All suspend methods run on [Dispatchers.IO].
 */
class AudiobookshelfApi(
    private val client: OkHttpClient,
    private val host: String,
    val auth: AudiobookshelfAuth,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /**
         * MIME types the player direct-plays. Sent wide so the server returns
         * direct-play tracks (playMethod 0) instead of transcoding.
         */
        val SUPPORTED_MIME_TYPES = listOf(
            "audio/mpeg", "audio/mp3",
            "audio/mp4", "audio/m4b", "audio/m4a", "audio/aac", "audio/x-m4a", "audio/x-m4b",
            "audio/flac", "audio/x-flac",
            "audio/wav", "audio/x-wav", "audio/aiff", "audio/x-aiff",
            "audio/ogg", "audio/opus",
        )
    }

    // -------------------------------------------------------------------------
    // Discovery (unauthenticated)
    // -------------------------------------------------------------------------

    /**
     * Unauthenticated `GET /status` — server auth-method discovery. Drives the
     * add-account form: local username/password fields and/or an SSO button.
     */
    suspend fun getStatus(): AbsServerStatus = withContext(Dispatchers.IO) {
        val url = buildUrl("/status")
        val request = Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AudiobookshelfApiException.TimedOut
        } catch (e: IOException) {
            throw AudiobookshelfApiException.Unreachable(e)
        }
        response.use { resp ->
            if (resp.code !in 200..299) {
                throw AudiobookshelfApiException.ServerError(resp.code)
            }
            decode(AbsServerStatus.serializer(), resp.body.string())
        }
    }

    // -------------------------------------------------------------------------
    // Libraries + items
    // -------------------------------------------------------------------------

    /** `GET /api/libraries`. Filter by [AbsLibrary.mediaType] client-side. */
    suspend fun getLibraries(): List<AbsLibrary> =
        get("/api/libraries", AbsLibrariesResponse.serializer()).libraries

    /**
     * `GET /api/libraries/{id}/items` — full item list for one library.
     * `limit=0` returns all; `minified=1` keeps the payload small.
     */
    suspend fun getLibraryItems(
        libraryId: String,
        sort: String = "media.metadata.title",
    ): List<AbsLibraryItem> = get(
        "/api/libraries/$libraryId/items",
        AbsLibraryItemsResponse.serializer(),
        // include=progress so list items carry userMediaProgress → progress
        // badges/subtitles work on the phone list AND Auto (beads_adagio-59p.1.7
        // E2). Harmless if a server ignores it. Live-server validation: 59p.2.5.
        params = mapOf("limit" to "0", "page" to "0", "minified" to "1", "include" to "progress", "sort" to sort),
    ).results

    /** `GET /api/items/{id}?expanded=1&include=progress` — item detail with the
     *  user's progress embedded and full `media.chapters[]`/`media.audioFiles[]`. */
    suspend fun getItem(id: String): AbsLibraryItem = get(
        "/api/items/$id",
        AbsLibraryItem.serializer(),
        params = mapOf("expanded" to "1", "include" to "progress"),
    )

    /** `GET /api/me` — the current user, including `permissions.download`. */
    suspend fun getMe(): AbsUser = get("/api/me", AbsUser.serializer())

    /** `GET /api/me/items-in-progress` — the mixed Continue Listening list.
     *  `recentEpisode != null` discriminates podcast episodes from books. */
    suspend fun getItemsInProgress(): List<AbsLibraryItem> =
        get("/api/me/items-in-progress", AbsItemsInProgressResponse.serializer()).libraryItems

    /**
     * `GET /api/me/progress/{libraryItemId}/{episodeId}` — this user's progress
     * for one podcast episode. Returns null when the server 404s (episode never
     * started), so a missing record reads as "unplayed" rather than a failure.
     */
    suspend fun getEpisodeProgress(libraryItemId: String, episodeId: String): AbsMediaProgress? {
        val request = Request.Builder()
            .url(buildUrl("/api/me/progress/$libraryItemId/$episodeId"))
            .get()
            .build()
        return auth.execute(request).use { resp ->
            when {
                resp.code == 404 -> null
                resp.code !in 200..299 -> throw AudiobookshelfApiException.ServerError(resp.code)
                else -> decode(AbsMediaProgress.serializer(), resp.body.string())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Playback sessions
    // -------------------------------------------------------------------------

    /**
     * `POST /api/items/{id}/play` (books) or `POST /api/items/{id}/play/{episodeId}`
     * (podcast episodes) — opens a playback session. The response carries the
     * global resume `currentTime`, direct-play `audioTracks[]`, chapters, and
     * the session id used for sync/close.
     */
    suspend fun openPlaybackSession(
        itemId: String,
        episodeId: String? = null,
        deviceId: String,
        clientVersion: String = "1.0",
    ): AbsPlaybackSession {
        val path = if (episodeId != null) {
            "/api/items/$itemId/play/$episodeId"
        } else {
            "/api/items/$itemId/play"
        }
        val body = json.encodeToString(
            PlayRequest(
                deviceInfo = PlayRequest.DeviceInfo(
                    deviceId = deviceId,
                    clientName = "AdagioStream",
                    clientVersion = clientVersion,
                ),
                mediaPlayer = "VLC",
                supportedMimeTypes = SUPPORTED_MIME_TYPES,
                forceDirectPlay = false,
                forceTranscode = false,
            ),
        )
        val request = Request.Builder()
            .url(buildUrl(path))
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(AudiobookshelfAuth.JSON_MEDIA_TYPE))
            .build()
        return auth.execute(request).use { resp ->
            if (resp.code !in 200..299) throw AudiobookshelfApiException.ServerError(resp.code)
            decode(AbsPlaybackSession.serializer(), resp.body.string())
        }
    }

    /**
     * `POST /api/session/{id}/sync` — reports progress. `currentTime` is
     * book-global. A 404 (expired/foreign session) surfaces distinctly as
     * [AudiobookshelfApiException.SessionNotFound] so the caller can reopen
     * via `/play`.
     */
    suspend fun syncSession(
        sessionId: String,
        currentTime: Double,
        timeListened: Double,
        duration: Double,
    ) {
        val body = json.encodeToString(
            SyncRequest(currentTime = currentTime, timeListened = timeListened, duration = duration),
        )
        val request = Request.Builder()
            .url(buildUrl("/api/session/$sessionId/sync"))
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(AudiobookshelfAuth.JSON_MEDIA_TYPE))
            .build()
        auth.execute(request).use { resp ->
            when {
                resp.code == 404 -> throw AudiobookshelfApiException.SessionNotFound
                resp.code !in 200..299 -> throw AudiobookshelfApiException.ServerError(resp.code)
            }
        }
    }

    /** `POST /api/session/{id}/close` — always call on stop; open sessions hold
     *  server resources. Best-effort: swallows all errors. */
    suspend fun closeSession(sessionId: String) {
        try {
            val request = Request.Builder()
                .url(buildUrl("/api/session/$sessionId/close"))
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(AudiobookshelfAuth.JSON_MEDIA_TYPE))
                .build()
            auth.execute(request).close()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    // -------------------------------------------------------------------------
    // Batched progress sync
    // -------------------------------------------------------------------------

    /**
     * `PATCH /api/me/progress/batch/update` — flushes queued progress in one
     * request. Body is a BARE JSON array of per-item progress objects;
     * `episodeId` is omitted entirely for books (see [AbsProgressUpdate]).
     */
    suspend fun batchUpdateProgress(updates: List<AbsProgressUpdate>) {
        if (updates.isEmpty()) return
        val body = json.encodeToString(updates)
        val request = Request.Builder()
            .url(buildUrl("/api/me/progress/batch/update"))
            .header("Content-Type", "application/json")
            .patch(body.toRequestBody(AudiobookshelfAuth.JSON_MEDIA_TYPE))
            .build()
        auth.execute(request).use { resp ->
            if (resp.code !in 200..299) throw AudiobookshelfApiException.ServerError(resp.code)
        }
    }

    // -------------------------------------------------------------------------
    // Media URLs
    // -------------------------------------------------------------------------

    /**
     * Cover art URL: `GET /api/items/{id}/cover?width=&format=&token=`.
     * Token-in-query because image loaders can't set headers reliably — never
     * log this URL. Null until first login (no access token yet) or if the
     * host is invalid.
     */
    fun coverUrl(itemId: String, width: Int = 400, format: String = "webp"): HttpUrl? {
        val token = auth.currentAccessToken() ?: return null
        return AudiobookshelfUrl.resolve(
            host,
            "/api/items/$itemId/cover",
            mapOf("width" to width.toString(), "format" to format, "token" to token),
        )
    }

    /**
     * Resolves a SERVER-RELATIVE content path (e.g. a session `audioTracks[]
     * .contentUrl`) against the host and appends the access token. Never log
     * this URL. Null until first login or if the host is invalid.
     */
    fun resolveContentUrl(contentPath: String): HttpUrl? {
        val token = auth.currentAccessToken() ?: return null
        return AudiobookshelfUrl.resolve(host, contentPath, mapOf("token" to token))
    }

    /**
     * URL for `GET /api/items/{id}/file/{ino}/download`. The access token goes
     * on the `Authorization: Bearer` header (set by the download task via
     * [AudiobookshelfAuth.currentAccessToken]), not the query — short-lived
     * JWTs shouldn't leak into URLs/logs.
     */
    fun fileDownloadUrl(itemId: String, ino: String): HttpUrl? =
        AudiobookshelfUrl.resolve(host, "/api/items/$itemId/file/$ino/download")

    /** The host this API is configured for — for stable cache-key construction. */
    val hostBase: String get() = host

    // -------------------------------------------------------------------------
    // Private: request helpers
    // -------------------------------------------------------------------------

    /** Authenticated GET that decodes the JSON body via [deserializer]. */
    private suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        params: Map<String, String> = emptyMap(),
    ): T {
        val request = Request.Builder().url(buildUrl(path, params)).get().build()
        return auth.execute(request).use { resp ->
            if (resp.code !in 200..299) throw AudiobookshelfApiException.ServerError(resp.code)
            decode(deserializer, resp.body.string())
        }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T = try {
        json.decodeFromString(deserializer, body)
    } catch (e: Exception) {
        throw AudiobookshelfApiException.DecodingError(e)
    }

    private fun buildUrl(path: String, params: Map<String, String> = emptyMap()): HttpUrl =
        AudiobookshelfUrl.resolve(host, path, params)
            ?: throw AudiobookshelfApiException.InvalidUrl("$host$path")

    /** Host only — tokens are never part of this object's printable state. */
    override fun toString(): String = "AudiobookshelfApi(host=$host)"

    // -------------------------------------------------------------------------
    // Request DTOs
    // -------------------------------------------------------------------------

    @kotlinx.serialization.Serializable
    private data class PlayRequest(
        val deviceInfo: DeviceInfo,
        val mediaPlayer: String,
        val supportedMimeTypes: List<String>,
        val forceDirectPlay: Boolean,
        val forceTranscode: Boolean,
    ) {
        @kotlinx.serialization.Serializable
        data class DeviceInfo(
            val deviceId: String,
            val clientName: String,
            val clientVersion: String,
        )
    }

    @kotlinx.serialization.Serializable
    private data class SyncRequest(
        val currentTime: Double,
        val timeListened: Double,
        val duration: Double,
    )
}
