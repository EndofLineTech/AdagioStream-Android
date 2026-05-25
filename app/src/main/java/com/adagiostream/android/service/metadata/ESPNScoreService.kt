package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.ESPNLeague
import com.adagiostream.android.model.ESPNScoreboardResponse
import com.adagiostream.android.model.ESPNTeam
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
    /** Sports channels with EPG data that weren't matched by team name — EPG fallback candidates. */
    private var epgCandidateChannels: Map<String, String> = emptyMap() // channelID → epgChannelID
    /** Pre-compiled search tokens per ESPN team displayName for EPG title matching. */
    private var teamTokenCache: MutableMap<String, TeamSearchTokens> = mutableMapOf()
    /** EPG data supplier, set by AccountManager after channel load. */
    var epgDataProvider: (() -> Map<String, List<EPGEntry>>)? = null
    private var hasChannels = false
    private var pollingWanted = false
    private var hasLiveGame = false
    private var pollJob: Job? = null
    private var lastScoreboardLogLine: String? = null

    var livePollIntervalMs: Long = 15_000L

    companion object {
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
        teamTokenCache.clear()

        // Build EPG candidate list: sports channels with EPG that didn't match a team name
        val matchedChannelIDs = teamToChannelIDs.values.flatten().toSet()
        epgCandidateChannels = sportsChannels
            .filter { it.epgChannelID != null && it.id !in matchedChannelIDs }
            .associate { it.id to it.epgChannelID!! }

        DebugLogger.log(
            "Matched ${teamToChannelIDs.size} team names from ${sportsChannels.size} sports channels, ${epgCandidateChannels.size} EPG fallback candidates",
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
        get() = if (hasLiveGame) livePollIntervalMs else IDLE_POLL_INTERVAL

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
                            val body = response.body.string()
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

            // Second pass: EPG-based fallback for unmatched sports channels
            matchEventsViaEPG(allEvents, newGames)

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

    private fun buildGameInfo(
        event: com.adagiostream.android.model.ESPNEvent,
        league: ESPNLeague,
    ): ESPNGameInfo? {
        val comp = event.competition ?: return null
        val home = comp.homeTeam ?: return null
        val away = comp.awayTeam ?: return null

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

        return ESPNGameInfo(
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
            balls = comp.situation?.balls,
            strikes = comp.situation?.strikes,
            onFirst = comp.situation?.onFirst,
            onSecond = comp.situation?.onSecond,
            onThird = comp.situation?.onThird,
            possessionTeamAbbr = possessionAbbr,
            downDistanceText = comp.situation?.shortDownDistanceText
                ?: comp.situation?.downDistanceText,
            gameDate = ESPNGameInfo.parseESPNDate(event.date),
        )
    }

    private fun matchEvents(
        events: List<com.adagiostream.android.model.ESPNEvent>,
        league: ESPNLeague,
        games: MutableMap<String, ESPNGameInfo>,
    ) {
        for (event in events) {
            val gameInfo = buildGameInfo(event, league) ?: continue
            val comp = event.competition ?: continue
            val home = comp.homeTeam ?: continue
            val away = comp.awayTeam ?: continue

            val homeKey = home.team.displayName.lowercase()
            teamToChannelIDs[homeKey]?.forEach { id -> games[id] = gameInfo }

            val awayKey = away.team.displayName.lowercase()
            teamToChannelIDs[awayKey]?.forEach { id -> games[id] = gameInfo }
        }
    }

    // MARK: - EPG Fuzzy Matching

    private data class TeamSearchTokens(
        val fullName: String,            // "boston bruins"
        val nicknamePattern: Regex,      // \bbruins\b
        val abbrPattern: Regex,          // \bBOS\b
    )

    private fun tokens(team: ESPNTeam): TeamSearchTokens {
        teamTokenCache[team.displayName]?.let { return it }
        val full = team.displayName.lowercase()
        val nickname = team.displayName.split(" ").lastOrNull() ?: team.displayName
        val result = TeamSearchTokens(
            fullName = full,
            nicknamePattern = Regex("\\b${Regex.escape(nickname)}\\b", RegexOption.IGNORE_CASE),
            abbrPattern = Regex("\\b${Regex.escape(team.abbreviation)}\\b", RegexOption.IGNORE_CASE),
        )
        teamTokenCache[team.displayName] = result
        return result
    }

    private fun epgTitleContainsTeam(title: String, tokens: TeamSearchTokens): Boolean {
        // Tier 1: full display name (most precise)
        if (title.lowercase().contains(tokens.fullName)) return true
        // Tier 2: team nickname with word boundary
        if (tokens.nicknamePattern.containsMatchIn(title)) return true
        // Tier 3: abbreviation with word boundary
        if (tokens.abbrPattern.containsMatchIn(title)) return true
        return false
    }

    private fun matchEventsViaEPG(
        allEvents: List<Pair<ESPNLeague, List<com.adagiostream.android.model.ESPNEvent>>>,
        games: MutableMap<String, ESPNGameInfo>,
    ) {
        if (epgCandidateChannels.isEmpty()) return
        val epgData = epgDataProvider?.invoke() ?: return

        var epgMatched = 0

        for ((channelID, epgID) in epgCandidateChannels) {
            if (games.containsKey(channelID)) continue
            val currentEntry = epgData[epgID]?.firstOrNull { it.isCurrentlyAiring } ?: continue
            val title = currentEntry.title

            var matched = false
            for ((league, events) in allEvents) {
                if (matched) break
                for (event in events) {
                    val comp = event.competition ?: continue
                    val home = comp.homeTeam ?: continue
                    val away = comp.awayTeam ?: continue
                    val gameInfo = buildGameInfo(event, league) ?: continue

                    val homeTokens = tokens(home.team)
                    val awayTokens = tokens(away.team)

                    // Require BOTH teams to appear in the EPG title to prevent false positives
                    if (epgTitleContainsTeam(title, homeTokens) && epgTitleContainsTeam(title, awayTokens)) {
                        games[channelID] = gameInfo
                        epgMatched++
                        matched = true
                        break
                    }
                }
            }
        }

        if (epgMatched > 0) {
            DebugLogger.log(
                "ESPN EPG fallback: matched $epgMatched channels via program titles",
                DebugLogger.Category.ESPN,
            )
        }
    }
}
