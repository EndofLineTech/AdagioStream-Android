package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * StellarTunerLog metadata API (api.stellartunerlog.com/v1) — the second
 * SiriusXM now-playing source (beads_adagio-59p.3.1, iOS parity:
 * `STLChannelListResponse`/`STLStation` in SXMTrack.swift).
 *
 * The API has no per-track timestamps, so [getRecentTrack] synthesizes
 * `startedAt` as the first time a (station, artist, title) combination was
 * observed, reusing the original time on re-observation — that keeps the
 * 10-minute history usable for time-shift catch-up via [trackAt].
 */
class StellarTunerLogApi(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.stellartunerlog.com/v1",
) {

    // coerceInputValues: an explicit JSON null for artist/title/cut_type
    // degrades to "" (fails the displayability check) instead of failing the
    // whole station decode — mirrors iOS's decodeIfPresent ?? "" leniency.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Fetch the channel catalog for name matching. Null on any failure so the
     * caller's retry loop (beads_adagio-59p.3.4) can back off and try again.
     */
    suspend fun fetchStations(): List<MatchableStation>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/channels").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("STL channels API returned ${response.code}", DebugLogger.Category.SXM)
                return@withContext null
            }
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            val channels = root["channels"]?.jsonObject ?: return@withContext null
            val decoded = channels.values.mapNotNull { decodeStationOrNull(it) }
            DebugLogger.log("Fetched ${decoded.size} STL channels", DebugLogger.Category.SXM)
            // Dictionary iteration order is nondeterministic — sort so
            // word-boundary matching always picks the same station.
            decoded.sortedBy { it.id }.map { MatchableStation(it.name, it.id) }
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch STL channels: ${e.message}", DebugLogger.Category.SXM)
            null
        }
    }

    /**
     * Fetch now-playing for every station in one call. Returns station ID →
     * displayable track (hidden cuts and empty stations drop). Used for
     * channel-list subtitles; feed entries carry no startedAt (timestamp 0).
     */
    suspend fun getFeed(): Map<String, TrackMetadata> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/nowplaying").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("STL nowplaying API returned ${response.code}", DebugLogger.Category.SXM)
                return@withContext emptyMap()
            }
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            val stations = root["stations"]?.jsonObject ?: return@withContext emptyMap()
            stations.values
                .mapNotNull { decodeStationOrNull(it) }
                .mapNotNull { station -> station.toTrackMetadataOrNull(0L)?.let { station.id to it } }
                .toMap()
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch STL feed: ${e.message}", DebugLogger.Category.SXM)
            emptyMap()
        }
    }

    /** One malformed dictionary entry drops instead of poisoning the whole response. */
    private fun decodeStationOrNull(element: JsonElement): STLStation? =
        runCatching { json.decodeFromJsonElement(STLStation.serializer(), element) }.getOrNull()

    /**
     * Fetch the current track for one station. `Result` distinguishes a fetch
     * failure (keep showing the last track — network blip) from a successful
     * fetch of a non-displayable cut (`success(null)` — ad break, clear the
     * display, mirroring iOS "Track cleared").
     */
    suspend fun getRecentTrack(
        stationId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<TrackMetadata?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/nowplaying/$stationId").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLogger.log("STL API returned ${response.code} for $stationId", DebugLogger.Category.SXM)
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            val station = json.decodeFromString<STLStationResponse>(response.body.string()).station
            // No per-track timestamp from this API: stamp first observation,
            // reusing the startedAt already in history for the same track.
            val startedAt = trackHistory[stationId]
                ?.firstOrNull { it.artist == station.artist && it.title == station.title }
                ?.timestamp ?: nowMillis
            val track = station.toTrackMetadataOrNull(startedAt)
            if (track != null) mergeIntoHistory(stationId, track)
            Result.success(track)
        } catch (e: Exception) {
            DebugLogger.log("Failed to fetch STL track for $stationId: ${e.message}", DebugLogger.Category.SXM)
            Result.failure(e)
        }
    }

    // Timestamped track history per station, newest-first — same 10-minute
    // window as XMPlaylistApi's, and the same ConcurrentHashMap rationale:
    // writes on Dispatchers.IO, reads from Main, values replaced wholesale
    // with a fresh immutable List.
    private val trackHistory = java.util.concurrent.ConcurrentHashMap<String, List<TrackMetadata>>()
    private val historyWindowMs = 10 * 60 * 1000L // 10 minutes

    private fun mergeIntoHistory(stationId: String, track: TrackMetadata) {
        val cutoff = System.currentTimeMillis() - historyWindowMs
        val merged = ((trackHistory[stationId] ?: emptyList()) + track)
            .distinct()
            .filter { it.timestamp >= cutoff }
            .sortedByDescending { it.timestamp }
        trackHistory[stationId] = merged
    }

    /**
     * The track that was playing at [atEpochMillis] — same contract as
     * [XMPlaylistApi.trackAt], including the query-time freshness bound.
     */
    fun trackAt(stationId: String, atEpochMillis: Long): TrackMetadata? {
        val minTimestamp = maxOf(1L, atEpochMillis - historyWindowMs)
        return trackHistory[stationId]?.firstOrNull { it.timestamp in minTimestamp..atEpochMillis }
    }

    /** Drop all synthesized-startedAt history — used on source switch (beads_adagio-59p.3.1). */
    fun clearHistory() = trackHistory.clear()
}

/**
 * Now-playing state for one station, from /v1/nowplaying[/{id}]. Only id/name
 * are required; artist/title/cut_type default to "" so a degenerate station
 * degrades to "no track" instead of failing the response decode.
 */
@Serializable
internal data class STLStation(
    val id: String,
    val name: String,
    val artist: String = "",
    val title: String = "",
    val album: String? = null,
    @SerialName("cut_type") val cutType: String = "",
    @SerialName("artwork_url") val artworkUrl: String? = null,
) {
    companion object {
        /**
         * Cut types that are never program content: Spot/Promo (ads and
         * promos), Fill (filler), Perm (placeholder where artist == channel
         * name). Link is NOT hidden: live-feed surveys show it overwhelmingly
         * carries real program data (DJ mix shows, comedy sets, podcast
         * episodes), and mix/comedy/podcast channels sit in Link for hours.
         * Live vocabulary is wide (Song, Music, talk, sports, Exp,
         * PGM_Segment, even the typo'd PGM_Segement), so this is a blocklist —
         * anything not listed is real metadata and shown.
         */
        private val HIDDEN_CUT_TYPES = setOf("spot", "promo", "fill", "perm")
    }

    /**
     * Whether this cut is real program metadata worth displaying.
     * Empty/missing cut_type hides too (the "commercial break" nil-track case).
     */
    val isDisplayable: Boolean
        get() {
            val cut = cutType.trim()
            return cut.isNotEmpty() && cut.lowercase() !in HIDDEN_CUT_TYPES
        }

    /**
     * Convert to the app track model. Null for non-displayable cuts, and for
     * displayable cuts with no artist and no title (nothing to show).
     * [startedAtMillis] is caller-supplied — the API has no per-track
     * timestamp, so the caller stamps first-observation time.
     */
    fun toTrackMetadataOrNull(startedAtMillis: Long): TrackMetadata? {
        if (!isDisplayable) return null
        if (artist.isBlank() && title.isBlank()) return null
        return TrackMetadata(
            artist = artist,
            title = title,
            album = album?.takeIf { it.isNotBlank() },
            albumArtURL = artworkUrl?.takeIf { UrlSanitizer.isHttpUrl(it) },
            timestamp = startedAtMillis,
        )
    }
}

/** Envelope for GET /v1/nowplaying/{channel_id} (single station, no history). */
@Serializable
internal data class STLStationResponse(
    val station: STLStation,
)
