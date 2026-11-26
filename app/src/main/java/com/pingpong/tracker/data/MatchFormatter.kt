package com.pingpong.tracker.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object MatchFormatter {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM · HH:mm", Locale.FRANCE)

    fun selectWidgetMatches(
        matches: List<MatchItem>,
        trackedClubs: List<String> = MatchStore.DEFAULT_CLUBS,
        maxCount: Int = 3,
    ): List<MatchItem> {
        val relevant = matches.filter { match ->
            !match.isClub || trackedClubs.any { club -> match.matchesClub(club) }
        }.ifEmpty { matches }

        val live = relevant
            .filter { it.isLive }
            .sortedBy { it.startInstant() ?: Instant.MAX }

        val scheduled = relevant
            .filter { it.isScheduled }
            .sortedBy { it.startInstant() ?: Instant.MAX }

        val active = live + scheduled
        if (active.isNotEmpty()) {
            return active.take(maxCount)
        }

        return relevant
            .filter { it.isFinished }
            .sortedByDescending { it.startInstant() ?: Instant.MIN }
            .take(maxCount)
    }

    fun sortMatchesForList(matches: List<MatchItem>): List<MatchItem> {
        val live = matches
            .filter { it.isLive }
            .sortedBy { it.startInstant() ?: Instant.MAX }

        val scheduled = matches
            .filter { it.isScheduled }
            .sortedBy { it.startInstant() ?: Instant.MAX }

        val finished = matches
            .filter { it.isFinished }
            .sortedByDescending { it.startInstant() ?: Instant.MIN }

        return live + scheduled + finished
    }

    fun badgeType(match: MatchItem, trackedClubs: List<String> = MatchStore.DEFAULT_CLUBS): BadgeType {
        if (match.isLive) return BadgeType.LIVE
        if (match.home.contains("bruille", ignoreCase = true) ||
            match.away.contains("bruille", ignoreCase = true) ||
            trackedClubs.any { club ->
                club.equals("CTT Bruille", ignoreCase = true) && match.matchesClub(club)
            }
        ) {
            return BadgeType.BRUILLE
        }
        if (match.category.equals(MatchItem.CATEGORY_CLUB, ignoreCase = true)) {
            return BadgeType.CLUB
        }
        return BadgeType.INTL
    }

    fun badgeColorHex(type: BadgeType): Int = when (type) {
        BadgeType.LIVE -> 0xFFE11D48.toInt()
        BadgeType.BRUILLE -> 0xFF22C55E.toInt()
        BadgeType.CLUB -> 0xFF3B82F6.toInt()
        BadgeType.INTL -> 0xFFF59E0B.toInt()
    }

    fun formatCompetition(match: MatchItem): String {
        return match.competition.trim().uppercase(Locale.FRANCE)
    }

    fun formatCountdown(
        match: MatchItem,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (match.isLive) return "EN DIRECT"
        if (match.isFinished) return "Terminé"

        val startInstant = match.startInstant() ?: return "À venir"
        val nowZoned = now.atZone(zoneId)
        val startZoned = startInstant.atZone(zoneId)
        val minutesUntil = Duration.between(now, startInstant).toMinutes()

        if (minutesUntil <= 0) {
            return "Imminent"
        }
        if (minutesUntil < 60) {
            return "Dans $minutesUntil min"
        }

        val timeText = startZoned.format(timeFormatter)
        val today = nowZoned.toLocalDate()
        val matchDay = startZoned.toLocalDate()

        return when {
            matchDay == today -> {
                val hours = minutesUntil / 60
                val mins = minutesUntil % 60
                String.format(Locale.FRANCE, "Auj. %s (%dh%02d)", timeText, hours, mins)
            }
            matchDay == today.plusDays(1) -> {
                "Demain $timeText"
            }
            else -> {
                startZoned.format(dateTimeFormatter)
            }
        }
    }

    fun formatMatchTitle(match: MatchItem): String {
        val score = match.score.trim()
        return if (score.isNotEmpty()) {
            "${match.home}  $score  ${match.away}"
        } else {
            "${match.home} vs ${match.away}"
        }
    }

    fun parseScorePair(score: String): Pair<String, String>? {
        val cleaned = score.trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split("-", ":", "–").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    fun formatMetaLine(match: MatchItem): String {
        val parts = ArrayList<String>(3)
        val followedText = match.followed.joinToString(" · ")
        if (followedText.isNotEmpty()) parts.add(followedText)
        val broadcastText = match.broadcast.trim()
        if (broadcastText.isNotEmpty()) parts.add(broadcastText)
        if (match.vpnRequired) {
            parts.add(match.vpnNote.trim().ifEmpty { "VPN conseillé" })
        }
        return parts.joinToString("  ·  ")
    }

    fun formatSyncLabel(
        lastSyncMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (lastSyncMs <= 0L) return "--:--"
        val formatted = Instant.ofEpochMilli(lastSyncMs)
            .atZone(zoneId)
            .format(timeFormatter)
        return formatted
    }
}
