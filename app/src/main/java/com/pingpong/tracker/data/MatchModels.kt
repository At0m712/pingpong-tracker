package com.pingpong.tracker.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class MatchFeed(
    val updatedAt: String,
    val updatedLabel: String,
    val trackedPlayers: List<String>,
    val trackedClub: String,
    val matches: List<MatchItem>,
    val tournaments: List<TournamentBracket> = emptyList(),
)

data class MatchItem(
    val id: String,
    val category: String,
    val competition: String,
    val home: String,
    val away: String,
    val startIso: String,
    val status: String,
    val score: String,
    val followed: List<String>,
    val broadcast: String,
    val url: String,
    val vpnRequired: Boolean = false,
    val vpnNote: String = "",
) {
    val isLive: Boolean
        get() = status.equals(STATUS_LIVE, ignoreCase = true)

    val isScheduled: Boolean
        get() = status.equals(STATUS_SCHEDULED, ignoreCase = true)

    val isFinished: Boolean
        get() = status.equals(STATUS_FINISHED, ignoreCase = true)

    val isClub: Boolean
        get() = category.equals(CATEGORY_CLUB, ignoreCase = true)

    val isInternational: Boolean
        get() = category.equals(CATEGORY_INTL, ignoreCase = true)

    fun startInstant(): Instant? {
        if (startIso.isBlank()) return null
        return try {
            OffsetDateTime.parse(startIso).toInstant()
        } catch (_: Exception) {
            try {
                ZonedDateTime.parse(startIso).toInstant()
            } catch (_: Exception) {
                try {
                    Instant.parse(startIso)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun startLocalDateTime(zoneId: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        return startInstant()?.atZone(zoneId)
    }

    fun matchesClub(clubName: String): Boolean {
        val needle = clubName.trim()
        if (needle.isEmpty()) return false
        return home.contains(needle, ignoreCase = true) ||
            away.contains(needle, ignoreCase = true) ||
            followed.any { it.contains(needle, ignoreCase = true) }
    }

    companion object {
        const val STATUS_LIVE = "live"
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_FINISHED = "finished"

        const val CATEGORY_CLUB = "CLUB"
        const val CATEGORY_INTL = "INTL"
    }
}

data class TournamentBracket(
    val id: String,
    val name: String,
    val location: String,
    val dates: String,
    val category: String,
    val rounds: List<BracketRound>,
)

data class BracketRound(
    val name: String,
    val matches: List<BracketMatch>,
)

data class BracketMatch(
    val id: String,
    val player1: String,
    val country1: String,
    val score1: String,
    val player2: String,
    val country2: String,
    val score2: String,
    val status: String,
    val winner: Int, // 0 = none, 1 = player1, 2 = player2
    val highlight1: Boolean,
    val highlight2: Boolean,
    val url: String,
    val vpnRequired: Boolean = false,
) {
    val isLive: Boolean
        get() = status.equals(MatchItem.STATUS_LIVE, ignoreCase = true)
}

enum class BadgeType {
    LIVE,
    BRUILLE,
    CLUB,
    INTL,
}
