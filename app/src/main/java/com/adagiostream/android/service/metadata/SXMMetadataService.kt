package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.SXMMetadataSource
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Station catalog entry used for name matching: display name + the identifier
 * stored in the channel→station map (xmplaylist deeplink, or STL channel id).
 */
data class MatchableStation(val name: String, val identifier: String)

/**
 * Owns the SiriusXM channel→station matching table and routes metadata
 * requests to the active source — xmplaylist.com or StellarTunerLog
 * (beads_adagio-59p.3.1, iOS parity: `SXMMetadataService`).
 *
 * The station-catalog fetch retries with capped exponential backoff instead of
 * failing once at launch (beads_adagio-59p.3.4 — the old single-shot
 * `XMPlaylistApi.matchChannels()` killed SXM metadata for the whole session on
 * one launch-time network failure). A generation counter supersedes stale
 * in-flight loops, and a late success re-attaches metadata to a channel that
 * started playing while the map was still empty (via [onMappingBuilt]).
 */
class SXMMetadataService(
    private val xmPlaylistApi: XMPlaylistApi,
    private val stellarTunerLogApi: StellarTunerLogApi,
    initialSource: SXMMetadataSource = SXMMetadataSource.STELLARTUNERLOG,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    /** Active metadata source; updated via [sourceChanged]. */
    private var source: SXMMetadataSource = initialSource

    // Channel ID → station identifier, built by matchChannels()
    private var channelStationMap: Map<String, String> = emptyMap()

    // Reverse: station identifier → list of channel IDs (for feed fan-out)
    private var stationToChannelIds: Map<String, List<String>> = emptyMap()

    /** Channels from the last matchChannels() call, retained so sourceChanged() can re-match. */
    private var lastMatchedChannels: List<Channel> = emptyList()
    private var lastSortPrefixes: List<String> = listOf("Radio: ", "TV: ")

    /**
     * Bumped by every [matchChannels] call; the station-list retry loop exits
     * when its captured generation is superseded (beads_adagio-59p.3.4).
     */
    private var matchGeneration = 0

    /**
     * Retained so tests can await loop completion; production never cancels it
     * — the generation guard supersedes stale loops.
     */
    var matchJob: Job? = null
        private set

    /**
     * Invoked on [scope]'s dispatcher after a matching table lands.
     * AccountManager wires this to update its SXM state, (re)start feed
     * polling, and re-attach metadata to an already-playing channel
     * (beads_adagio-59p.3.4 late success).
     */
    var onMappingBuilt: (() -> Unit)? = null

    // MARK: - Test Seams (beads_adagio-59p.3.4, iOS parity: SXMMetadataRetryTests)

    /** When set, consulted instead of the real network fetch in the retry loop. */
    internal var stationListFetcher: (suspend () -> List<MatchableStation>?)? = null

    /**
     * Backoff sleep for the retry loop. Returns true when the sleep was
     * cancelled — the loop must exit instead of hot-looping with zero delay.
     * The production default never returns true (a cancelled `delay` throws
     * CancellationException, unwinding the loop's coroutine); the Boolean
     * exists so tests can pin the cooperative-exit path deterministically.
     */
    internal var retrySleep: suspend (Long) -> Boolean = { seconds ->
        delay(seconds * 1000L)
        false
    }

    // MARK: - Channel Matching

    /**
     * Build the channel→station lookup table for the active source. Launches
     * an async retry loop — callers observe completion via [onMappingBuilt].
     */
    fun matchChannels(
        channels: List<Channel>,
        sortPrefixes: List<String> = listOf("Radio: ", "TV: "),
    ) {
        matchGeneration += 1
        lastMatchedChannels = channels
        lastSortPrefixes = sortPrefixes

        val sxmPattern = Regex("(?i)\\b(siriusxm|sirius\\s*xm|sxm|sirius|xm)\\b")
        val sxmChannels = channels.filter { sxmPattern.containsMatchIn(it.group) }
        if (sxmChannels.isEmpty()) {
            DebugLogger.log("No SXM channels found in ${channels.size} channels", DebugLogger.Category.SXM)
            return
        }
        DebugLogger.log("Found ${sxmChannels.size} SXM channels, fetching ${source.serialName} station list...", DebugLogger.Category.SXM)

        val generation = matchGeneration
        matchJob = scope.launch {
            // Retry until the list loads or a newer matchChannels() supersedes
            // this loop — a single failed fetch at launch must not disable SXM
            // metadata for the whole session (beads_adagio-59p.3.4).
            var delaySeconds = 5L
            while (true) {
                val fetcher = stationListFetcher
                val stations = if (fetcher != null) fetcher() else fetchStationList()
                if (generation != matchGeneration) return@launch
                if (stations != null) {
                    buildMatchingTable(sxmChannels, stations, sortPrefixes)
                    return@launch
                }
                DebugLogger.log("Station list fetch failed; retrying in ${delaySeconds}s", DebugLogger.Category.SXM)
                // Cancelled sleep = exit; otherwise a cancelled loop would spin
                // zero-delay hot retries (test seam only — see retrySleep).
                if (retrySleep(delaySeconds)) return@launch
                if (generation != matchGeneration) return@launch
                delaySeconds = minOf(delaySeconds * 2, 60L)
            }
        }
    }

    private suspend fun fetchStationList(): List<MatchableStation>? = when (source) {
        SXMMetadataSource.XMPLAYLIST -> xmPlaylistApi.fetchStations()
        SXMMetadataSource.STELLARTUNERLOG -> stellarTunerLogApi.fetchStations()
    }

    private fun buildMatchingTable(
        sxmChannels: List<Channel>,
        stations: List<MatchableStation>,
        sortPrefixes: List<String>,
    ) {
        // Build into a fresh map and assign only on success — the live map is
        // never left cleared or half-built by a failed/stale fetch.
        val result = buildStationMap(sxmChannels, stations, sortPrefixes)
        channelStationMap = result
        stationToChannelIds = result.entries.groupBy({ it.value }, { it.key })
        onMappingBuilt?.invoke()
    }

    // MARK: - Source Switching

    /**
     * Called when the user changes the metadata source in Settings
     * (beads_adagio-59p.3.1). Old-source identifiers mean nothing to the new
     * source, so the map clears immediately (lookups miss → non-SXM rather
     * than mixed), per-source track history drops, and matching re-runs. The
     * live channel resumes via the caller's [onMappingBuilt] late-attach.
     */
    fun sourceChanged(newSource: SXMMetadataSource) {
        if (newSource == source) return
        source = newSource
        DebugLogger.log("Metadata source changed to ${newSource.serialName}, re-matching channels", DebugLogger.Category.SXM)
        channelStationMap = emptyMap()
        stationToChannelIds = emptyMap()
        xmPlaylistApi.clearHistory()
        stellarTunerLogApi.clearHistory()
        matchChannels(lastMatchedChannels, lastSortPrefixes)
    }

    // MARK: - Lookups & Metadata

    /** Get the station identifier for a channel by its ID. */
    fun stationIdForChannel(channelId: String): String? = channelStationMap[channelId]

    /** Whether any channels have been mapped to stations. */
    fun hasMappedChannels(): Boolean = channelStationMap.isNotEmpty()

    /**
     * Fetch the feed of current tracks across all mapped SXM channels.
     * Returns app channel ID → track.
     */
    suspend fun getFeed(): Map<String, TrackMetadata> {
        val byStation = when (source) {
            SXMMetadataSource.XMPLAYLIST -> xmPlaylistApi.getFeed()
            SXMMetadataSource.STELLARTUNERLOG -> stellarTunerLogApi.getFeed()
        }
        val result = mutableMapOf<String, TrackMetadata>()
        for ((stationId, metadata) in byStation) {
            val channelIds = stationToChannelIds[stationId] ?: continue
            for (channelId in channelIds) {
                result[channelId] = metadata
            }
        }
        return result
    }

    /**
     * Fetch the current track for a station. `success(null)` means the source
     * answered with nothing displayable (ad break — clear the display);
     * `failure` means the fetch itself failed (keep the last shown track).
     */
    suspend fun getRecentTrack(stationId: String): Result<TrackMetadata?> = when (source) {
        SXMMetadataSource.XMPLAYLIST -> xmPlaylistApi.getRecentTrack(stationId)
        SXMMetadataSource.STELLARTUNERLOG -> stellarTunerLogApi.getRecentTrack(stationId)
    }

    /** The track that was playing at [atEpochMillis] — time-shift catch-up lookup. */
    fun trackAt(stationId: String, atEpochMillis: Long): TrackMetadata? = when (source) {
        SXMMetadataSource.XMPLAYLIST -> xmPlaylistApi.trackAt(stationId, atEpochMillis)
        SXMMetadataSource.STELLARTUNERLOG -> stellarTunerLogApi.trackAt(stationId, atEpochMillis)
    }

    companion object {
        /**
         * Pure three-tier matching core (exact → word-boundary → unmatched),
         * lifted unchanged from the old XMPlaylistApi.matchChannels() so both
         * sources share it. App channel → station identifier.
         */
        fun buildStationMap(
            sxmChannels: List<Channel>,
            stations: List<MatchableStation>,
            sortPrefixes: List<String>,
        ): Map<String, String> {
            // Build normalized station lookup: normalized name → station
            val stationLookup = mutableMapOf<String, MatchableStation>()
            for (station in stations) {
                stationLookup.putIfAbsent(station.name.lowercase().trim(), station)
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
                    result[channel.id] = exactStation.identifier
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
                        result[channel.id] = station.identifier
                        wordMatches++
                        matched = true
                        break
                    }
                }

                if (!matched) {
                    DebugLogger.log("Unmatched SXM channel: '${channel.name}'", DebugLogger.Category.SXM)
                }
            }

            DebugLogger.log(
                "Matched ${result.size}/${sxmChannels.size} channels " +
                    "(exact=$exactMatches, word=$wordMatches)",
                DebugLogger.Category.SXM,
            )
            return result
        }
    }
}
