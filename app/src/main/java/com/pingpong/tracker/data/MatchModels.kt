package com.pingpong.tracker.data

import java.text.Normalizer
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
    val players: List<PlayerProfile> = emptyList(),
    val standings: List<ClubStanding> = emptyList(),
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
    val lequipeUrl: String = "",
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
        val needle = normalizeText(clubName)
        if (needle.isEmpty()) return false
        return normalizeText(home).contains(needle) ||
            normalizeText(away).contains(needle) ||
            followed.any { normalizeText(it).contains(needle) }
    }

    fun matchesPlayer(playerName: String): Boolean {
        val needle = normalizeText(playerName)
        if (needle.isEmpty()) return false
        val haystack = normalizeText("$home $away ${followed.joinToString(" ")}")
        if (haystack.contains(needle)) return true
        // Also match by last name if not ambiguous ("lebrun" needs first name)
        val parts = needle.split(" ").filter { it.length >= 3 }
        if (parts.size >= 2) {
            val last = parts.last()
            val first = parts.first()
            return if (last == "lebrun") {
                haystack.contains(first) && haystack.contains("lebrun")
            } else {
                haystack.contains(last)
            }
        }
        return false
    }

    companion object {
        private fun normalizeText(s: String): String {
            return Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        }
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

data class PlayerProfile(
    val name: String,
    val country: String = "FRA",
    val club: String = "",
    val worldRank: String = "—",
    val winRate: String = "—",
    val record: String = "",
    val titles: String = "—",
    val photoUrl: String = "",
    val history: List<PlayerTournamentResult> = emptyList(),
)

data class PlayerTournamentResult(
    val tournament: String,
    val date: String,
    val stage: String,
    val detail: String = "",
    val discipline: String = "SINGLE", // SINGLE, DOUBLE, MIXED
)

data class ClubStanding(
    val rank: Int,
    val club: String,
    val played: Int,
    val wins: Int,
    val points: Int,
)


