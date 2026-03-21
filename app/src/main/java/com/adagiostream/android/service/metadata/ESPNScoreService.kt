package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.ESPNLeague
import com.adagiostream.android.model.ESPNScoreboardResponse
import com.adagiostream.android.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class ESPNScoreService(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _gamesByChannel = MutableStateFlow<Map<String, ESPNGameInfo>>(emptyMap())
    val gamesByChannel: StateFlow<Map<String, ESPNGameInfo>> = _gamesByChannel

    /** Lowercase team displayName → list of channel IDs */
    private var teamToChannelIDs: Map<String, List<String>> = emptyMap()
    private var hasChannels = false
    private var pollingWanted = false
    private var hasLiveGame = false
    private var pollJob: Job? = null
    private var lastScoreboardLogLine: String? = null

    companion object {
        private const val LIVE_POLL_INTERVAL = 15_000L
        private const val IDLE_POLL_INTERVAL = 60_000L
        private val SPORTS_LEAGUES = setOf("NFL", "MLB", "NBA", "NHL")
        private val CHANNEL_PREFIXES = listOf("Radio: ", "TV: ")
        private val ALL_LEAGUES = listOf(ESPNLeague.MLB, ESPNLeague.NBA, ESPNLeague.NHL, ESPNLeague.NFL)
        private const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports"
    }

    // MARK: - Channel Matching

    /**
     * Call after channels are loaded. Extracts team names from sports channel names
     * and builds a lookup table for matching ESPN events.
     */
    fun matchChannels(channels: List<Channel>, sortPrefixes: List<String> = emptyList()) {
        teamToChannelIDs = emptyMap()
        _gamesByChannel.value = emptyMap()

        val sportsChannels = channels.filter { channel ->
            val upperGroup = channel.group.uppercase()
            SPORTS_LEAGUES.any { upperGroup.contains(it) }
        }

        if (sportsChannels.isEmpty()) {
            hasChannels = false
            DebugLogger.log("No sports channels found for ESPN matching", DebugLogger.Category.ESPN)
            return
        }

        val lookup = mutableMapOf<String, MutableList<String>>()
        for (channel in sportsChannels) {
            var teamName = channel.name
            for (prefix in CHANNEL_PREFIXES + sortPrefixes) {
                if (teamName.startsWith(prefix, ignoreCase = true)) {
                    teamName = teamName.removeRange(0, prefix.length)
                    break
                }
            }
            val normalized = teamName.lowercase().trim()
            lookup.getOrPut(normalized) { mutableListOf() }.add(channel.id)
        }

        teamToChannelIDs = lookup
        hasChannels = true
        DebugLogger.log(
            "Matched ${teamToChannelIDs.size} team names from ${sportsChannels.size} sports channels",
            DebugLogger.Category.ESPN,
        )

        if (pollingWanted) {
            startPolling()
        }
    }

    // MARK: - Polling Control

    fun setPollingEnabled(enabled: Boolean) {
        pollingWanted = enabled
        if (enabled && hasChannels && pollJob == null) {
            startPolling()
        } else if (!enabled) {
            stopPolling()
        }
    }

    private val currentPollInterval: Long
        get() = if (hasLiveGame) LIVE_POLL_INTERVAL else IDLE_POLL_INTERVAL

    private fun startPolling() {
        stopPolling()
        val interval = currentPollInterval
        DebugLogger.log(
            "Starting ESPN score polling (interval=${interval / 1000}s, live=$hasLiveGame)",
            DebugLogger.Category.ESPN,
        )
        pollJob = scope.launch {
            while (true) {
                fetchAllScoreboards()
                delay(interval)
            }
        }
    }

    private fun adjustPollRateIfNeeded() {
        val wasLive = hasLiveGame
        hasLiveGame = _gamesByChannel.value.values.any { it.state == ESPNGameInfo.GameState.LIVE }
        if (hasLiveGame != wasLive && pollingWanted) {
            DebugLogger.log(
                "ESPN poll rate changed: live=$hasLiveGame, interval=${currentPollInterval / 1000}s",
                DebugLogger.Category.ESPN,
            )
            startPolling() // restart with new interval
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // MARK: - Fetch & Match

    private suspend fun fetchAllScoreboards() {
        try {
            val allEvents = mutableListOf<Pair<ESPNLeague, List<com.adagiostream.android.model.ESPNEvent>>>()
            val fetchedLeagues = mutableSetOf<ESPNLeague>()

            // Parallel fetch all leagues
            coroutineScope {
                val deferreds = ALL_LEAGUES.map { league ->
                    async(Dispatchers.IO) {
                        try {
                            val url = "$BASE_URL/${league.sportPath}/scoreboard"
                            val request = Request.Builder().url(url).build()
                            val response = client.newCall(request).execute()
                            if (!response.isSuccessful) return@async null
                            val body = response.body?.string() ?: return@async null
                            val scoreboard = json.decodeFromString<ESPNScoreboardResponse>(body)
                            league to scoreboard.events
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                for (deferred in deferreds) {
                    deferred.await()?.let { allEvents.add(it) }
                }
            }

            // Only update data for leagues that responded successfully.
            // Keep existing entries for leagues that failed, so a transient
            // network blip on one league doesn't wipe live-game state and
            // cause poll-rate thrashing between 15s ↔ 60s.
            val newGames = _gamesByChannel.value.toMutableMap()
            for ((league, _) in allEvents) { fetchedLeagues.add(league) }

            // Clear entries for leagues that responded (will be re-populated below)
            val entriesToRemove = newGames.entries.filter { fetchedLeagues.contains(it.value.league) }
            for (entry in entriesToRemove) { newGames.remove(entry.key) }

            var totalEvents = 0
            for ((league, events) in allEvents) {
                totalEvents += events.size
                matchEvents(events, league, newGames)
            }

            if (_gamesByChannel.value != newGames) {
                _gamesByChannel.value = newGames
            }
            adjustPollRateIfNeeded()

            val scoreLogLine = "ESPN scoreboard: $totalEvents events across ${allEvents.size} leagues, ${newGames.size} matched to channels"
            if (scoreLogLine != lastScoreboardLogLine) {
                lastScoreboardLogLine = scoreLogLine
                DebugLogger.log(scoreLogLine, DebugLogger.Category.ESPN)
            }
        } catch (e: Exception) {
            DebugLogger.log("ESPN fetch failed: ${e.message}", DebugLogger.Category.ESPN)
        }
    }

    private fun matchEvents(
        events: List<com.adagiostream.android.model.ESPNEvent>,
        league: ESPNLeague,
        games: MutableMap<String, ESPNGameInfo>,
    ) {
        for (event in events) {
            val comp = event.competition ?: continue
            val home = comp.homeTeam ?: continue
            val away = comp.awayTeam ?: continue

            // Resolve NFL possession to team abbreviation
            var possessionAbbr: String? = null
            if (league == ESPNLeague.NFL) {
                val possID = comp.situation?.possession
                if (possID != null) {
                    possessionAbbr = when (possID) {
                        home.team.id -> home.team.abbreviation
                        away.team.id -> away.team.abbreviation
                        else -> null
                    }
                }
            }

            val gameInfo = ESPNGameInfo(
                league = league,
                awayAbbr = away.team.abbreviation,
                homeAbbr = home.team.abbreviation,
                awayScore = away.score,
                homeScore = home.score,
                awayRecord = away.overallRecord,
                homeRecord = home.overallRecord,
                state = ESPNGameInfo.GameState.fromApi(comp.status.type.state),
                statusDetail = comp.status.type.shortDetail,
                displayClock = comp.status.displayClock,
                period = comp.status.period,
                outs = comp.situation?.outs,
                possessionTeamAbbr = possessionAbbr,
                downDistanceText = comp.situation?.shortDownDistanceText
                    ?: comp.situation?.downDistanceText,
            )

            // Match home team
            val homeKey = home.team.displayName.lowercase()
            teamToChannelIDs[homeKey]?.forEach { id -> games[id] = gameInfo }

            // Match away team
            val awayKey = away.team.displayName.lowercase()
            teamToChannelIDs[awayKey]?.forEach { id -> games[id] = gameInfo }
        }
    }
}
