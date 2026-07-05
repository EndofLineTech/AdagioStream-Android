package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.Channel
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

class XMPlaylistApi(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://xmplaylist.com/api",
) {

    private val json = Json { ignoreUnknownKeys = true }

    // Channel ID → deeplink mapping, built by matchChannels()
    private var channelDeeplinkMap: Map<String, String> = emptyMap()

    // Reverse: deeplink → list of channel IDs
    private var deeplinkToChannelIds: Map<String, List<String>> = emptyMap()

    /**
     * Fetch the XMPlaylist station list and match against app channels.
     * Uses the same three-tier matching as the iOS version:
     * 1. Exact match (normalized name)
     * 2. Word-boundary match (regex)
     * 3. Unmatched (logged)
     */
    suspend fun matchChannels(
        channels: List<Channel>,
        sortPrefixes: List<String> = listOf("Radio: ", "TV: "),
    ) = withContext(Dispatchers.IO) {
        try {
            // Filter to SXM-related channels by group name
            val sxmPattern = Regex("(?i)\\b(siriusxm|sirius\\s*xm|sxm|sirius|xm)\\b")
            val sxmChannels = channels.filter { sxmPattern.containsMatchIn(it.group) }
            if (sxmChannels.isEmpty()) {
                DebugLogger.log("No SXM channels found in ${channels.size} channels", DebugLogger.Category.SXM)
                return@withContext
            }
            DebugLogger.log("Found ${sxmChannels.size} SXM channels to match", DebugLogger.Category.SXM)

            // Fetch station list
            val request = Request.Builder().url("$baseUrl/station").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("Station list API returned ${response.code}", DebugLogger.Category.SXM)
                return@withContext
            }
            val body = response.body.string()
            val stationList = json.decodeFromString<XMStationListResponse>(body)
            val stations = stationList.results
            DebugLogger.log("Fetched ${stations.size} XMPlaylist stations", DebugLogger.Category.SXM)

            // Build normalized station lookup: normalized name → station
            val stationLookup = mutableMapOf<String, XMStation>()
            for (station in stations) {
                stationLookup[station.name.lowercase().trim()] = station
            }

            val result = mutableMapOf<String, String>()
            var exactMatches = 0
            var wordMatches = 0

            for (channel in sxmChannels) {
                // Strip sort prefixes (e.g., "Radio: ") from channel name
                var stripped = channel.name
                for (prefix in sortPrefixes) {
                    if (stripped.startsWith(prefix, ignoreCase = true)) {
                        stripped = stripped.removeRange(0, prefix.length)
                        break
                    }
                }
                val normalized = stripped.lowercase().trim()

                // Tier 1: Exact match
                val exactStation = stationLookup[normalized]
                if (exactStation != null) {
                    result[channel.id] = exactStation.deeplink
                    exactMatches++
                    continue
                }

                // Tier 2: Word-boundary match
                var matched = false
                for (station in stations) {
                    val stationNorm = station.name.lowercase().trim()
                    val channelPattern = Regex("\\b${Regex.escape(normalized)}\\b")
                    val stationPattern = Regex("\\b${Regex.escape(stationNorm)}\\b")
                    if (channelPattern.containsMatchIn(stationNorm) ||
                        stationPattern.containsMatchIn(normalized)
                    ) {
                        result[channel.id] = station.deeplink
                        wordMatches++
                        matched = true
                        break
                    }
                }

                if (!matched) {
                    DebugLogger.log("Unmatched SXM channel: '${channel.name}'", DebugLogger.Category.SXM)
                }
            }

            channelDeeplinkMap = result
            deeplinkToChannelIds = result.entries
                .groupBy({ it.value }, { it.key })

            DebugLogger.log("Matched ${result.size}/${sxmChannels.size} channels " +
                    "(exact=$exactMatches, word=$wordMatches)", DebugLogger.Category.SXM)
        } catch (e: Exception) {
            DebugLogger.log("Failed to match channels: ${e.message}", DebugLogger.Category.SXM)
        }
    }

    /** Get the deeplink (slug) for a channel by its ID. */
    fun deeplinkForChannel(channelId: String): String? = channelDeeplinkMap[channelId]

    /** Whether any channels have been mapped to XM deeplinks. */
    fun hasMappedChannels(): Boolean = channelDeeplinkMap.isNotEmpty()

    /**
     * Fetch the feed of recent tracks across all SXM channels.
     * Returns a map of channelId (app) → TrackMetadata.
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

            // Map deeplinks back to app channel IDs
            val result = mutableMapOf<String, TrackMetadata>()
            for ((deeplink, metadata) in byDeeplink) {
                val channelIds = deeplinkToChannelIds[deeplink] ?: continue
                for (channelId in channelIds) {
                    result[channelId] = metadata
                }
            }
            result
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch feed: ${e.message}", DebugLogger.Category.SXM)
            emptyMap()
        }
    }

    /**
     * Fetch the most recent track for a specific station by deeplink.
     * Also merges every track in the response into the 10-minute [trackHistory]
     * so [trackAt] can answer "what was playing" for time-shift catch-up.
     */
    suspend fun getRecentTrack(deeplink: String): TrackMetadata? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/station/$deeplink").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("API returned ${response.code} for $deeplink", DebugLogger.Category.SXM)
                return@withContext null
            }
            val body = response.body.string()
            val apiResponse = json.decodeFromString<XMApiResponse>(body)
            val tracks = apiResponse.results.mapNotNull { it.toTrackMetadataOrNull() }
            mergeIntoHistory(deeplink, tracks)
            tracks.firstOrNull()
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch track for $deeplink: ${e.message}", DebugLogger.Category.SXM)
            null
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
