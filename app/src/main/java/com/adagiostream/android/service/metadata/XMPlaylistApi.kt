package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * xmplaylist.com metadata API. Pure API client — channel→station matching and
 * source selection live in [SXMMetadataService] (beads_adagio-59p.3.1/.4).
 */
class XMPlaylistApi(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://xmplaylist.com/api",
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch the station catalog for name matching. Null on any failure so the
     * caller's retry loop (beads_adagio-59p.3.4) can back off and try again.
     */
    suspend fun fetchStations(): List<MatchableStation>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/station").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("Station list API returned ${response.code}", DebugLogger.Category.SXM)
                return@withContext null
            }
            val stations = json.decodeFromString<XMStationListResponse>(response.body.string()).results
            DebugLogger.log("Fetched ${stations.size} XMPlaylist stations", DebugLogger.Category.SXM)
            stations.map { MatchableStation(it.name, it.deeplink) }
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch station list: ${e.message}", DebugLogger.Category.SXM)
            null
        }
    }

    /**
     * Fetch the feed of recent tracks across all SXM stations.
     * Returns a map of deeplink → newest TrackMetadata.
     */
    suspend fun getFeed(): Map<String, TrackMetadata> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/feed").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("Feed API returned ${response.code}", DebugLogger.Category.SXM)
                return@withContext emptyMap()
            }
            val body = response.body.string()
            val apiResponse = json.decodeFromString<XMApiResponse>(body)

            // Group by deeplink (channelId in feed), take most recent per deeplink
            val byDeeplink = mutableMapOf<String, TrackMetadata>()
            for (entry in apiResponse.results) {
                val deeplink = entry.channelId ?: continue
                if (deeplink in byDeeplink) continue
                val track = entry.track ?: continue
                val title = track.title ?: continue
                val artists = track.artists
                if (artists.isEmpty()) continue
                byDeeplink[deeplink] = TrackMetadata(
                    artist = artists.joinToString(", "),
                    title = title,
                    album = null,
                    albumArtURL = entry.spotify?.albumImageLarge?.takeIf { UrlSanitizer.isHttpUrl(it) },
                    timestamp = 0L,
                )
            }
            byDeeplink
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch feed: ${e.message}", DebugLogger.Category.SXM)
            emptyMap()
        }
    }

    /**
     * Fetch the most recent track for a specific station by deeplink.
     * Also merges every track in the response into the 10-minute [trackHistory]
     * so [trackAt] can answer "what was playing" for time-shift catch-up.
     *
     * `Result` distinguishes a fetch failure (keep showing the last track —
     * network blip) from a successful fetch with no track (`success(null)` —
     * commercial break, clear the display, mirroring iOS "Track cleared").
     */
    suspend fun getRecentTrack(deeplink: String): Result<TrackMetadata?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/station/$deeplink").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("API returned ${response.code} for $deeplink", DebugLogger.Category.SXM)
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            val body = response.body.string()
            val apiResponse = json.decodeFromString<XMApiResponse>(body)
            val tracks = apiResponse.results.mapNotNull { it.toTrackMetadataOrNull() }
            mergeIntoHistory(deeplink, tracks)
            Result.success(tracks.firstOrNull())
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch track for $deeplink: ${e.message}", DebugLogger.Category.SXM)
            Result.failure(e)
        }
    }

    private fun XMResult.toTrackMetadataOrNull(): TrackMetadata? {
        val title = track?.title ?: return null
        val artists = track.artists
        if (artists.isEmpty()) return null
        return TrackMetadata(
            artist = artists.joinToString(", "),
            title = title,
            album = null,
            albumArtURL = spotify?.albumImageLarge?.takeIf { UrlSanitizer.isHttpUrl(it) },
            timestamp = parseTimestampMillis(timestamp),
        )
    }

    private fun parseTimestampMillis(iso: String?): Long {
        if (iso == null) return 0L
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    // Timestamped track history per deeplink, newest-first — mirrors iOS
    // SXMMetadataService's 10-minute trackHistory used by showTrack(at:).
    // ConcurrentHashMap (baw.13 MAJOR-2): mergeIntoHistory() writes on
    // Dispatchers.IO (poll loop) while trackAt() reads from Main; values are
    // always replaced wholesale with a fresh immutable List, so a thread-safe
    // map (no per-key locking) is sufficient — no partial-list mutation to race on.
    private val trackHistory = java.util.concurrent.ConcurrentHashMap<String, List<TrackMetadata>>()
    private val historyWindowMs = 10 * 60 * 1000L // 10 minutes

    private fun mergeIntoHistory(deeplink: String, tracks: List<TrackMetadata>) {
        if (tracks.isEmpty()) return
        val cutoff = System.currentTimeMillis() - historyWindowMs
        val merged = ((trackHistory[deeplink] ?: emptyList()) + tracks)
            .distinct()
            .filter { it.timestamp >= cutoff }
            .sortedByDescending { it.timestamp }
        trackHistory[deeplink] = merged
    }

    /**
     * The track that was playing at [atEpochMillis] — the most recent history
     * entry whose timestamp is at or before that moment. Used during time-shift
     * catch-up so the display track matches the buffered audio, not live.
     * Null when history is empty or [atEpochMillis] predates everything kept
     * (history only spans [historyWindowMs]).
     *
     * The freshness bound is relative to [atEpochMillis] (the query time), not
     * just [mergeIntoHistory]'s poll-time pruning (baw.13 MINOR-4): if polling
     * has stopped, stale entries older than [historyWindowMs] would otherwise
     * never get pruned and could still be returned as a "hit".
     */
    fun trackAt(deeplink: String, atEpochMillis: Long): TrackMetadata? {
        val minTimestamp = maxOf(1L, atEpochMillis - historyWindowMs)
        return trackHistory[deeplink]?.firstOrNull { it.timestamp in minTimestamp..atEpochMillis }
    }

    /** Drop all track history — used on source switch (beads_adagio-59p.3.1). */
    fun clearHistory() = trackHistory.clear()

    // --- API Response Models ---

    @Serializable
    private data class XMStationListResponse(
        @SerialName("results") val results: List<XMStation> = emptyList(),
    )

    @Serializable
    private data class XMStation(
        @SerialName("name") val name: String = "",
        @SerialName("deeplink") val deeplink: String = "",
    )

    @Serializable
    private data class XMApiResponse(
        @SerialName("results") val results: List<XMResult> = emptyList(),
    )

    @Serializable
    private data class XMResult(
        @SerialName("track") val track: XMTrackInfo? = null,
        @SerialName("spotify") val spotify: XMSpotifyInfo? = null,
        @SerialName("channelId") val channelId: String? = null,
        @SerialName("timestamp") val timestamp: String? = null,
    )

    @Serializable
    private data class XMTrackInfo(
        @SerialName("title") val title: String? = null,
        @SerialName("artists") val artists: List<String> = emptyList(),
    )

    @Serializable
    private data class XMSpotifyInfo(
        @SerialName("albumImageLarge") val albumImageLarge: String? = null,
    )
}
