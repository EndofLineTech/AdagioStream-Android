package com.adagiostream.android.service.navidrome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
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
    // Browse endpoints (baw.2.1)
    // -------------------------------------------------------------------------

    /**
     * Fetches all artists from `getArtists.view`, flattening the alphabetical
     * index buckets into a single sorted [List<Artist>].
     *
     * Subsonic response payload:
     * ```json
     * {"artists":{"index":[{"name":"A","artist":[…]}]}}
     * ```
     */
    suspend fun getArtists(): List<Artist> {
        val url = buildUrl("getArtists", emptyMap())
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/getArtists.view")
        val now = nowEpochSeconds()
        val payload = fetchAndDecode(url, GetArtistsPayload.serializer())
        return payload.response.artists?.index
            ?.flatMap { bucket -> bucket.artists.map { it.toRecord(updatedAt = now) } }
            ?: emptyList()
    }

    /**
     * Fetches a single artist and their albums from `getArtist.view?id=`.
     *
     * Returns the [Artist] record and the list of [Album] records.
     *
     * Subsonic response payload:
     * ```json
     * {"artist":{"id":…,"name":…,"album":[…]}}
     * ```
     */
    suspend fun getArtist(id: String): Pair<Artist, List<Album>> {
        val url = buildUrl("getArtist", mapOf("id" to id))
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/getArtist.view")
        val now = nowEpochSeconds()
        val payload = fetchAndDecode(url, GetArtistPayload.serializer())
        val artistBody = payload.response.artist
            ?: throw NavidromeApiException.DecodingError(
                IllegalStateException("Missing 'artist' in getArtist response"),
            )
        val artist = artistBody.toArtistRecord(updatedAt = now)
        val albums = artistBody.albums.map { it.toRecord(updatedAt = now) }
        return artist to albums
    }

    /**
     * Fetches an album and its tracks from `getAlbum.view?id=`.
     *
     * Returns the [Album] record, the human-readable artist display name
     * (from the `"artist"` Subsonic field — never the `artistId`), and
     * the list of [Track] records.
     *
     * Subsonic response payload:
     * ```json
     * {"album":{"id":…,"title":…,"artist":"Human Name","song":[…]}}
     * ```
     */
    suspend fun getAlbum(id: String): Triple<Album, String?, List<Track>> {
        val url = buildUrl("getAlbum", mapOf("id" to id))
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/getAlbum.view")
        val now = nowEpochSeconds()
        val payload = fetchAndDecode(url, GetAlbumPayload.serializer())
        val albumBody = payload.response.album
            ?: throw NavidromeApiException.DecodingError(
                IllegalStateException("Missing 'album' in getAlbum response"),
            )
        val album = albumBody.toAlbumRecord(updatedAt = now)
        val tracks = albumBody.songs.map { it.toRecord(updatedAt = now) }
        return Triple(album, albumBody.artistName, tracks)
    }

    /**
     * Fetches a page of albums from `getAlbumList2.view`.
     *
     * @param type    Ordering/filter type (newest, recent, random, etc.).
     * @param size    Page size — Navidrome accepts 1–500; default 50.
     * @param offset  Pagination offset.
     */
    suspend fun getAlbumList2(
        type: AlbumListType = AlbumListType.NEWEST,
        size: Int = 50,
        offset: Int = 0,
    ): List<Album> {
        val params = mapOf(
            "type" to type.apiValue,
            "size" to size.toString(),
            "offset" to offset.toString(),
        )
        val url = buildUrl("getAlbumList2", params)
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/getAlbumList2.view")
        val now = nowEpochSeconds()
        val payload = fetchAndDecode(url, GetAlbumListPayload.serializer())
        return payload.response.albumList2?.albums?.map { it.toRecord(updatedAt = now) }
            ?: emptyList()
    }

    /**
     * Fetches all genres from `getGenres.view`.
     *
     * Genres with zero songs/albums are included — the server typically omits
     * empty genres, but the caller should filter if needed.
     */
    suspend fun getGenres(): List<SubsonicGenre> {
        val url = buildUrl("getGenres", emptyMap())
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/getGenres.view")
        val payload = fetchAndDecode(url, GetGenresPayload.serializer())
        return payload.response.genres?.genres ?: emptyList()
    }

    /**
     * Fetches songs for a specific genre from `getSongsByGenre.view`.
     *
     * @param genre   Genre name exactly as returned by [getGenres].
     * @param count   Maximum number of songs to return; default 50.
     * @param offset  Pagination offset.
     */
    suspend fun getSongsByGenre(
        genre: String,
        count: Int = 50,
        offset: Int = 0,
    ): List<Track> {
        val params = mapOf(
            "genre" to genre,
            "count" to count.toString(),
            "offset" to offset.toString(),
        )
        val url = buildUrl("getSongsByGenre", params)
            ?: throw NavidromeApiException.InvalidUrl("$host/rest/getSongsByGenre.view")
        val now = nowEpochSeconds()
        val payload = fetchAndDecode(url, GetSongsByGenrePayload.serializer())
        return payload.response.songsByGenre?.songs?.map { it.toRecord(updatedAt = now) }
            ?: emptyList()
    }

    // -------------------------------------------------------------------------
    // Cover art (baw.2.2)
    // -------------------------------------------------------------------------

    /**
     * Builds an authenticated `getCoverArt.view` URL for the given [coverArtId].
     *
     * The URL includes a fresh Subsonic auth salt on every call.
     * Callers that use Coil for image loading MUST key the cache on a STABLE
     * key derived from [host], [coverArtId], and [size] — NOT on this URL.
     * The [NavidromeCoverArtKeyer] handles this.
     *
     * @param coverArtId  Subsonic cover-art identifier from Artist/Album/Track.
     * @param size        Optional thumbnail pixel size (square).
     * @return            The fully-qualified authenticated URL, or `null` if the
     *                    host string is not a valid HTTP/HTTPS URL.
     */
    fun getCoverArtUrl(coverArtId: String, size: Int? = null): HttpUrl? {
        val params = buildMap<String, String> {
            put("id", coverArtId)
            if (size != null) put("size", size.toString())
        }
        return buildUrl("getCoverArt", params)
    }

    /** The host this API is configured for — exposed for stable Coil cache-key construction. */
    val hostBase: String get() = host

    // -------------------------------------------------------------------------
    // Private: browse helper
    // -------------------------------------------------------------------------

    /**
     * Fetches [url], checks the Subsonic status envelope, then decodes the full
     * response JSON with [deserializer].
     *
     * The two-pass decode mirrors the iOS `fetch(_:params:as:)` generic helper:
     * 1. Status-only decode (via [decodeEnvelope] / [checkStatus]) to surface
     *    authentication and protocol errors as typed [NavidromeApiException]s.
     * 2. Full-body decode with the caller-supplied [deserializer].
     *
     * Throws [NavidromeApiException.DecodingError] if the payload decode fails.
     */
    private suspend fun <T> fetchAndDecode(
        url: HttpUrl,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val data = fetchRawData(url)
        val envelope = decodeEnvelope(data)
        checkStatus(envelope)
        return try {
            json.decodeFromString(deserializer, String(data, Charsets.UTF_8))
        } catch (e: Exception) {
            throw NavidromeApiException.DecodingError(e)
        }
    }

    /** Current time as Unix epoch seconds — used for [Artist.updatedAt] etc. */
    private fun nowEpochSeconds(): Int = (System.currentTimeMillis() / 1000L).toInt()

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
