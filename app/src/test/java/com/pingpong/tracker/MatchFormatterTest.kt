package com.pingpong.tracker

import com.pingpong.tracker.data.BadgeType
import com.pingpong.tracker.data.MatchFormatter
import com.pingpong.tracker.data.MatchItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MatchFormatterTest {

    private val parisZone = ZoneId.of("Europe/Paris")

    private fun sampleMatch(
        id: String = "m1",
        category: String = "CLUB",
        competition: String = "Pro A · J7",
        home: String = "CTT Bruille",
        away: String = "SPO Rouen",
        startIso: String = "2026-11-26T19:30:00+01:00",
        status: String = "scheduled",
        score: String = "",
        followed: List<String> = listOf("Bruille / Flavien Coton", "Thibault Poret"),
        broadcast: String = "YouTube FFTT",
        url: String = "https://www.youtube.com/@FFTT_Officiel/streams",
    ) = MatchItem(
        id = id,
        category = category,
        competition = competition,
        home = home,
        away = away,
        startIso = startIso,
        status = status,
        score = score,
        followed = followed,
        broadcast = broadcast,
        url = url,
    )

    @Test
    fun badgeTypeFollowsPriorityRules() {
        assertEquals(BadgeType.LIVE, MatchFormatter.badgeType(sampleMatch(status = "live")))
        assertEquals(BadgeType.BRUILLE, MatchFormatter.badgeType(sampleMatch(home = "CTT Bruille")))
        assertEquals(
            BadgeType.CLUB,
            MatchFormatter.badgeType(sampleMatch(home = "Alliance Nîmes", away = "Pontoise", category = "CLUB")),
        )
        assertEquals(
            BadgeType.INTL,
            MatchFormatter.badgeType(sampleMatch(home = "Félix Lebrun", away = "Lin Shidong", category = "INTL")),
        )
    }

    @Test
    fun countdownFormatsAllStates() {
        val now = Instant.parse("2026-11-26T17:00:00Z") // 18:00 Europe/Paris

        assertEquals("EN DIRECT", MatchFormatter.formatCountdown(sampleMatch(status = "live"), now, parisZone))
        assertEquals("Terminé", MatchFormatter.formatCountdown(sampleMatch(status = "finished"), now, parisZone))
        assertEquals(
            "Dans 30 min",
            MatchFormatter.formatCountdown(sampleMatch(startIso = "2026-11-26T18:30:00+01:00"), now, parisZone),
        )
        assertEquals(
            "Auj. 19:30 (1h30)",
            MatchFormatter.formatCountdown(sampleMatch(startIso = "2026-11-26T19:30:00+01:00"), now, parisZone),
        )
        assertEquals(
            "Demain 20:00",
            MatchFormatter.formatCountdown(sampleMatch(startIso = "2026-11-27T20:00:00+01:00"), now, parisZone),
        )
        assertEquals(
            "29/11 · 15:00",
            MatchFormatter.formatCountdown(sampleMatch(startIso = "2026-11-29T15:00:00+01:00"), now, parisZone),
        )
    }

    @Test
    fun widgetSelectionPrioritizesLiveThenScheduledWithFinishedFallback() {
        val mFinished = sampleMatch(id = "fin", status = "finished", startIso = "2026-11-25T19:00:00+01:00")
        val mSchedLate = sampleMatch(id = "s2", status = "scheduled", startIso = "2026-11-26T21:00:00+01:00")
        val mSchedEarly = sampleMatch(id = "s1", status = "scheduled", startIso = "2026-11-26T19:30:00+01:00")
        val mLive = sampleMatch(id = "live", status = "live", startIso = "2026-11-26T18:00:00+01:00")

        val selected = MatchFormatter.selectWidgetMatches(listOf(mFinished, mSchedLate, mSchedEarly, mLive))
        assertEquals(listOf("live", "s1", "s2"), selected.map { it.id })

        val fallback = MatchFormatter.selectWidgetMatches(listOf(mFinished))
        assertEquals(listOf("fin"), fallback.map { it.id })
    }

    @Test
    fun titleAndMetaFormatting() {
        val mNoScore = sampleMatch(score = "")
        assertEquals("CTT Bruille vs SPO Rouen", MatchFormatter.formatMatchTitle(mNoScore))
        assertEquals(
            "Bruille / Flavien Coton · Thibault Poret  ·  YouTube FFTT",
            MatchFormatter.formatMetaLine(mNoScore),
        )

        val mWithScore = sampleMatch(score = "3 - 1")
        assertEquals("CTT Bruille  3 - 1  SPO Rouen", MatchFormatter.formatMatchTitle(mWithScore))
    }
}
