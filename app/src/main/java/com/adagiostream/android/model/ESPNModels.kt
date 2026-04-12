package com.adagiostream.android.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// MARK: - ESPN Scoreboard API Response

@Serializable
data class ESPNScoreboardResponse(
    val events: List<ESPNEvent> = emptyList(),
)

@Serializable
data class ESPNEvent(
    val id: String,
    val date: String = "",                    // ISO-8601: "2024-03-30T17:05Z"
    val shortName: String = "",               // "MIN @ BOS"
    val competitions: List<ESPNCompetition> = emptyList(),
) {
    val competition: ESPNCompetition? get() = competitions.firstOrNull()
}

@Serializable
data class ESPNCompetition(
    val competitors: List<ESPNCompetitor> = emptyList(),
    val status: ESPNStatus,
    val situation: ESPNSituation? = null,
) {
    val homeTeam: ESPNCompetitor? get() = competitors.firstOrNull { it.homeAway == "home" }
    val awayTeam: ESPNCompetitor? get() = competitors.firstOrNull { it.homeAway == "away" }
}

@Serializable
data class ESPNSituation(
    // MLB
    val outs: Int? = null,
    val balls: Int? = null,
    val strikes: Int? = null,
    val onFirst: Boolean? = null,
    val onSecond: Boolean? = null,
    val onThird: Boolean? = null,
    // NFL
    val possession: String? = null,            // team ID with possession
    val down: Int? = null,
    val distance: Int? = null,
    val yardLine: Int? = null,
    val downDistanceText: String? = null,       // "1st & 10 at GB 25"
    val shortDownDistanceText: String? = null,  // "1st & 10"
    val possessionText: String? = null,         // "Green Bay Packers"
)

@Serializable
data class ESPNCompetitor(
    val homeAway: String = "",                 // "home" or "away"
    val score: String = "0",                   // "0", "3", etc.
    val team: ESPNTeam,
    val records: List<ESPNRecord>? = null,
) {
    val overallRecord: String?
        get() = records?.firstOrNull { it.name == "overall" }?.summary
}

@Serializable
data class ESPNTeam(
    val id: String = "",
    val displayName: String = "",              // "Boston Red Sox"
    val abbreviation: String = "",             // "BOS"
)

@Serializable
data class ESPNRecord(
    val name: String = "",                     // "overall"
    val summary: String = "",                  // "9-11"
)

@Serializable
data class ESPNStatus(
    val displayClock: String? = null,          // "8:35", "0:00"
    val period: Int? = null,                   // 1, 2, 3, 4
    val type: ESPNStatusType,
)

@Serializable
data class ESPNStatusType(
    val state: String = "",                    // "pre", "in", "post"
    val shortDetail: String = "",              // "3/15 - 1:05 PM EDT", "Bot 7th", "Final"
)

// MARK: - League

enum class ESPNLeague(val sportPath: String, val periodName: String) {
    MLB("baseball/mlb", ""),              // MLB uses innings, handled by shortDetail
    NBA("basketball/nba", "Quarter"),
    NHL("hockey/nhl", "Period"),
    NFL("football/nfl", ""),              // NFL uses custom format
}

// MARK: - Resolved Game Info for Display

/**
 * A matched ESPN game ready for display in a channel row.
 */
data class ESPNGameInfo(
    val league: ESPNLeague,
    val awayAbbr: String,                      // "MIN"
    val homeAbbr: String,                      // "BOS"
    val awayScore: String,                     // "3"
    val homeScore: String,                     // "0"
    val awayRecord: String?,                   // "7-13-1"
    val homeRecord: String?,                   // "9-11"
    val state: GameState,                      // .PRE, .LIVE, .POST
    val statusDetail: String,                  // "Bot 7th", "8:35 - 1st", "Final"
    val displayClock: String?,                 // "8:35"
    val period: Int?,                          // 1-4
    // MLB
    val outs: Int? = null,                     // 0-3 during live game
    val balls: Int? = null,                    // 0-3
    val strikes: Int? = null,                  // 0-2
    val onFirst: Boolean? = null,
    val onSecond: Boolean? = null,
    val onThird: Boolean? = null,
    // NFL
    val possessionTeamAbbr: String? = null,    // "GB" — team with the ball
    val downDistanceText: String? = null,      // "1st & 10"
    // Game date (parsed from ISO-8601 event date)
    val gameDate: Date? = null,
) {
    enum class GameState(val apiValue: String) {
        PRE("pre"),
        LIVE("in"),
        POST("post");

        companion object {
            fun fromApi(state: String): GameState =
                entries.firstOrNull { it.apiValue == state } ?: PRE
        }
    }

    val isMLBLive: Boolean get() = league == ESPNLeague.MLB && state == GameState.LIVE

    /**
     * Formats the game start time in the user's local timezone.
     * Shows "Today, 1:05 PM" or "3/30, 1:05 PM" depending on the date.
     * Falls back to statusDetail if gameDate was not parsed.
     */
    private val localStartTime: String
        get() {
            val date = gameDate ?: return statusDetail
            return if (Calendar.getInstance().let { cal ->
                    val now = cal.time
                    cal.time = date
                    val gameDay = cal.get(Calendar.DAY_OF_YEAR)
                    val gameYear = cal.get(Calendar.YEAR)
                    cal.time = now
                    gameDay == cal.get(Calendar.DAY_OF_YEAR) && gameYear == cal.get(Calendar.YEAR)
                }) {
                "Today, ${timeFormatter.format(date)}"
            } else {
                dateTimeFormatter.format(date)
            }
        }

    // MARK: - One-liner (channel row, mini player)

    val displayText: String
        get() = when (state) {
            GameState.PRE -> "${awayAbbr}${recordText(awayRecord)} @ ${homeAbbr}${recordText(homeRecord)} · $localStartTime"
            GameState.LIVE -> "$scoreLine · $liveDetail"
            GameState.POST -> "$scoreLine · $statusDetail"
        }

    // MARK: - Two-line (Now Playing, Android Auto)

    /** Line 1: score line */
    val nowPlayingTitle: String
        get() = when (state) {
            GameState.PRE -> "${awayAbbr}${recordText(awayRecord)} @ ${homeAbbr}${recordText(homeRecord)}"
            GameState.LIVE, GameState.POST -> scoreLine
        }

    /** Line 2: game status */
    val nowPlayingSubtitle: String
        get() = when (state) {
            GameState.LIVE -> liveDetail
            GameState.PRE -> localStartTime
            GameState.POST -> statusDetail
        }

    // MARK: - Private Formatting

    private val scoreLine: String
        get() = when (league) {
            ESPNLeague.NFL -> {
                // Football emoji next to team with possession
                val awayPoss = if (possessionTeamAbbr == awayAbbr) "\uD83C\uDFC8 " else ""
                val homePoss = if (possessionTeamAbbr == homeAbbr) "\uD83C\uDFC8 " else ""
                "${awayPoss}${awayAbbr} $awayScore - ${homePoss}${homeAbbr} $homeScore"
            }
            else -> "$awayAbbr $awayScore - $homeAbbr $homeScore"
        }

    private val liveDetail: String
        get() = when (league) {
            ESPNLeague.MLB -> "$statusDetail$countText$outsText$basesText"
            ESPNLeague.NBA, ESPNLeague.NHL -> {
                val clock = displayClock ?: ""
                val periodStr = period?.let { "${ordinal(it)} ${league.periodName}" } ?: ""
                if (clock.isEmpty()) periodStr else "$clock, $periodStr"
            }
            ESPNLeague.NFL -> {
                val parts = mutableListOf<String>()
                downDistanceText?.let { parts.add(it) }
                period?.let { parts.add("Q$it") }
                displayClock?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                parts.joinToString(", ")
            }
        }

    private val countText: String
        get() {
            val b = balls ?: return ""
            val s = strikes ?: return ""
            return ", $b-$s"
        }

    private val outsText: String
        get() {
            val o = outs ?: return ""
            return ", $o ${if (o == 1) "Out" else "Outs"}"
        }

    private val basesText: String
        get() {
            if (onFirst == null && onSecond == null && onThird == null) return ""
            val b3 = if (onThird == true) "\u25C6" else "\u25C7"
            val b2 = if (onSecond == true) "\u25C6" else "\u25C7"
            val b1 = if (onFirst == true) "\u25C6" else "\u25C7"
            return " $b3$b2$b1"
        }

    private fun recordText(record: String?): String =
        record?.let { " ($it)" } ?: ""

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }

    companion object {
        /** Parses ESPN's ISO-8601 event date (e.g. "2024-03-30T17:05Z") */
        fun parseESPNDate(dateString: String): Date? {
            if (dateString.isBlank()) return null
            return try {
                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
                formatter.timeZone = TimeZone.getTimeZone("UTC")
                formatter.parse(dateString)
            } catch (_: Exception) {
                null
            }
        }

        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.US)
        private val dateTimeFormatter = SimpleDateFormat("M/d, h:mm a", Locale.US)
    }
}
