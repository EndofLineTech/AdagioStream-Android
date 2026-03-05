package com.adagiostream.android.service.metadata

import android.util.Log
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class XMPlaylistApi(private val client: OkHttpClient) {

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
                Log.d(TAG, "No SXM channels found in ${channels.size} channels")
                return@withContext
            }
            Log.d(TAG, "Found ${sxmChannels.size} SXM channels to match")

            // Fetch station list
            val request = Request.Builder().url("$BASE_URL/station").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Station list API returned ${response.code}")
                return@withContext
            }
            val body = response.body?.string() ?: return@withContext
            val stationList = json.decodeFromString<XMStationListResponse>(body)
            val stations = stationList.results
            Log.d(TAG, "Fetched ${stations.size} XMPlaylist stations")

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
                    Log.d(TAG, "Unmatched SXM channel: '${channel.name}'")
                }
            }

            channelDeeplinkMap = result
            deeplinkToChannelIds = result.entries
                .groupBy({ it.value }, { it.key })

            Log.d(TAG, "Matched ${result.size}/${sxmChannels.size} channels " +
                    "(exact=$exactMatches, word=$wordMatches)")
        } catch (e: Exception) {
            Log.d(TAG, "Failed to match channels: ${e.message}")
        }
    }

    /** Get the deeplink (slug) for a channel by its ID. */
    fun deeplinkForChannel(channelId: String): String? = channelDeeplinkMap[channelId]

    /**
     * Fetch the feed of recent tracks across all SXM channels.
     * Returns a map of channelId (app) → TrackMetadata.
     */
    suspend fun getFeed(): Map<String, TrackMetadata> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/feed").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Feed API returned ${response.code}")
                return@withContext emptyMap()
            }
            val body = response.body?.string() ?: return@withContext emptyMap()
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
                    albumArtURL = entry.spotify?.albumImageLarge,
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
            Log.d(TAG, "Failed to fetch feed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Fetch the most recent track for a specific station by deeplink.
     */
    suspend fun getRecentTrack(deeplink: String): TrackMetadata? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/station/$deeplink").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "API returned ${response.code} for $deeplink")
                return@withContext null
            }
            val body = response.body?.string() ?: return@withContext null
            val apiResponse = json.decodeFromString<XMApiResponse>(body)
            val latest = apiResponse.results.firstOrNull() ?: return@withContext null
            val track = latest.track ?: return@withContext null
            val title = track.title ?: return@withContext null
            val artists = track.artists
            if (artists.isEmpty()) return@withContext null

            TrackMetadata(
                artist = artists.joinToString(", "),
                title = title,
                album = null,
                albumArtURL = latest.spotify?.albumImageLarge,
                timestamp = 0L,
            )
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch track for $deeplink: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "XMPlaylistApi"
        private const val BASE_URL = "https://xmplaylist.com/api"
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
