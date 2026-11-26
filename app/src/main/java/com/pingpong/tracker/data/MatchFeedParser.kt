package com.pingpong.tracker.data

import org.json.JSONArray
import org.json.JSONObject

object MatchFeedParser {

    fun parse(rawJson: String): MatchFeed {
        val root = JSONObject(rawJson)
        val updatedAt = root.optString("updated_at", "")
        val updatedLabel = root.optString("updated_label", "")
        val trackedPlayers = root.optJSONArray("tracked_players").toStringList()
        val trackedClub = root.optString("tracked_club", "CTT Bruille")

        val matchesArray = root.optJSONArray("matches") ?: JSONArray()
        val matches = ArrayList<MatchItem>(matchesArray.length())

        for (i in 0 until matchesArray.length()) {
            val obj = matchesArray.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").ifBlank { "match_$i" }
            matches.add(
                MatchItem(
                    id = id,
                    category = obj.optString("category", MatchItem.CATEGORY_INTL),
                    competition = obj.optString("competition", ""),
                    home = obj.optString("home", ""),
                    away = obj.optString("away", ""),
                    startIso = obj.optString("start_iso", ""),
                    status = obj.optString("status", MatchItem.STATUS_SCHEDULED),
                    score = obj.optString("score", ""),
                    followed = obj.optJSONArray("followed").toStringList(),
                    broadcast = obj.optString("broadcast", ""),
                    url = obj.optString("url", ""),
                    vpnRequired = obj.optBoolean("vpn_required", false),
                    vpnNote = obj.optString("vpn_note", ""),
                )
            )
        }

        val tournaments = parseTournaments(root.optJSONArray("tournaments"))

        return MatchFeed(
            updatedAt = updatedAt,
            updatedLabel = updatedLabel,
            trackedPlayers = trackedPlayers,
            trackedClub = trackedClub,
            matches = matches,
            tournaments = tournaments,
        )
    }

    private fun parseTournaments(array: JSONArray?): List<TournamentBracket> {
        if (array == null) return emptyList()
        val list = ArrayList<TournamentBracket>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val roundsArr = obj.optJSONArray("rounds") ?: JSONArray()
            val rounds = ArrayList<BracketRound>(roundsArr.length())
            for (r in 0 until roundsArr.length()) {
                val rObj = roundsArr.optJSONObject(r) ?: continue
                val mArr = rObj.optJSONArray("matches") ?: JSONArray()
                val bMatches = ArrayList<BracketMatch>(mArr.length())
                for (m in 0 until mArr.length()) {
                    val mObj = mArr.optJSONObject(m) ?: continue
                    bMatches.add(
                        BracketMatch(
                            id = mObj.optString("id", "bm_${i}_${r}_${m}"),
                            player1 = mObj.optString("player1", "À déterminer"),
                            country1 = mObj.optString("country1", ""),
                            score1 = mObj.optString("score1", ""),
                            player2 = mObj.optString("player2", "À déterminer"),
                            country2 = mObj.optString("country2", ""),
                            score2 = mObj.optString("score2", ""),
                            status = mObj.optString("status", MatchItem.STATUS_SCHEDULED),
                            winner = mObj.optInt("winner", 0),
                            highlight1 = mObj.optBoolean("highlight1", false),
                            highlight2 = mObj.optBoolean("highlight2", false),
                            url = mObj.optString("url", ""),
                            vpnRequired = mObj.optBoolean("vpn_required", false),
                        )
                    )
                }
                rounds.add(
                    BracketRound(
                        name = rObj.optString("name", "Tour ${r + 1}"),
                        matches = bMatches,
                    )
                )
            }
            list.add(
                TournamentBracket(
                    id = obj.optString("id", "t_$i"),
                    name = obj.optString("name", "Tournoi WTT"),
                    location = obj.optString("location", ""),
                    dates = obj.optString("dates", ""),
                    category = obj.optString("category", "WTT"),
                    rounds = rounds,
                )
            )
        }
        return list
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "").trim()
            if (value.isNotEmpty()) {
                list.add(value)
            }
        }
        return list
    }
}
