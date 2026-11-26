package com.pingpong.tracker.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class MatchStore(context: Context) {

    private val appContext = context.applicationContext

    val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val notifiedPrefs: SharedPreferences =
        appContext.getSharedPreferences(NOTIFIED_PREFS_NAME, Context.MODE_PRIVATE)

    fun getFeedUrl(): String {
        return prefs.getString(KEY_FEED_URL, DEFAULT_FEED_URL)
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: DEFAULT_FEED_URL
    }

    fun setFeedUrl(url: String) {
        val cleaned = url.trim().ifEmpty { DEFAULT_FEED_URL }
        prefs.edit().putString(KEY_FEED_URL, cleaned).apply()
    }

    fun getTrackedClubs(): List<String> {
        val raw = prefs.getString(KEY_TRACKED_CLUBS, null)
        if (raw.isNullOrBlank()) {
            return DEFAULT_CLUBS
        }
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optString(i, "").trim()
                if (c.isNotEmpty()) result.add(c)
            }
            if (result.isEmpty()) DEFAULT_CLUBS else result
        } catch (_: Exception) {
            DEFAULT_CLUBS
        }
    }

    fun addTrackedClub(clubName: String): Boolean {
        val cleaned = clubName.trim()
        if (cleaned.isEmpty()) return false
        val current = getTrackedClubs().toMutableList()
        if (current.any { it.equals(cleaned, ignoreCase = true) }) {
            return false
        }
        current.add(cleaned)
        saveTrackedClubs(current)
        return true
    }

    fun removeTrackedClub(clubName: String): Boolean {
        val current = getTrackedClubs().toMutableList()
        // Always keep at least one club, and protect CTT Bruille only if it's the last one
        if (current.size <= 1) return false
        val removed = current.removeAll { it.equals(clubName, ignoreCase = true) }
        if (removed) {
            saveTrackedClubs(current)
        }
        return removed
    }

    private fun saveTrackedClubs(clubs: List<String>) {
        val arr = JSONArray()
        for (c in clubs) arr.put(c)
        prefs.edit().putString(KEY_TRACKED_CLUBS, arr.toString()).apply()
    }

    fun saveFeedJson(rawJson: String, syncEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_CACHED_JSON, rawJson)
            .putLong(KEY_LAST_SYNC_MS, syncEpochMs)
            .remove(KEY_LAST_ERROR)
            .putBoolean(KEY_IS_SYNCING, false)
            .apply()
    }

    fun getCachedFeed(): MatchFeed? {
        val raw = prefs.getString(KEY_CACHED_JSON, null) ?: return null
        return try {
            MatchFeedParser.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun getLastSyncEpochMs(): Long = prefs.getLong(KEY_LAST_SYNC_MS, 0L)

    fun setLastError(message: String?) {
        val editor = prefs.edit().putBoolean(KEY_IS_SYNCING, false)
        if (message.isNullOrBlank()) {
            editor.remove(KEY_LAST_ERROR)
        } else {
            editor.putString(KEY_LAST_ERROR, message)
        }
        editor.apply()
    }

    fun getLastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun setSyncing(syncing: Boolean) {
        prefs.edit().putBoolean(KEY_IS_SYNCING, syncing).apply()
    }

    fun isSyncing(): Boolean = prefs.getBoolean(KEY_IS_SYNCING, false)

    fun hasNotified(key: String): Boolean = notifiedPrefs.contains(key)

    fun markNotified(key: String, nowMs: Long = System.currentTimeMillis()) {
        notifiedPrefs.edit().putLong(key, nowMs).apply()
    }

    fun cleanupOldNotifications(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - SEVEN_DAYS_MS
        val editor = notifiedPrefs.edit()
        var changed = false
        for ((key, value) in notifiedPrefs.all) {
            val ts = value as? Long ?: 0L
            if (ts < cutoff) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) {
            editor.apply()
        }
    }

    companion object {
        const val DEFAULT_FEED_URL =
            "https://TON_PSEUDO.github.io/pingpong-tracker/matches.json"

        val DEFAULT_CLUBS = listOf("CTT Bruille")

        val SUGGESTED_CLUBS = listOf(
            "CTT Bruille",
            "SPO Rouen",
            "AS Pontoise-Cergy",
            "Alliance Nîmes",
            "GV Hennebont",
            "Morez Haut-Jura",
            "Les Loups d'Angers",
            "C'Chartres TT",
            "Saint-Denis US 93",
            "Thorigné-Fouillard",
        )

        const val PREFS_NAME = "pingpong_tracker_prefs"
        private const val NOTIFIED_PREFS_NAME = "pingpong_notified_matches"

        const val KEY_FEED_URL = "feed_url"
        const val KEY_TRACKED_CLUBS = "tracked_clubs"
        const val KEY_CACHED_JSON = "cached_json"
        const val KEY_LAST_SYNC_MS = "last_sync_ms"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_IS_SYNCING = "is_syncing"

        private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

        fun key30m(matchId: String): String = "${matchId}__30m"
        fun keyLive(matchId: String): String = "${matchId}__live"
    }
}
