package com.pingpong.tracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pingpong.tracker.data.BracketMatch
import com.pingpong.tracker.data.MatchFormatter
import com.pingpong.tracker.data.MatchItem
import com.pingpong.tracker.data.MatchStore
import com.pingpong.tracker.data.TournamentBracket
import com.pingpong.tracker.notifications.NotificationHelper
import com.pingpong.tracker.ui.PinkDustBackgroundView
import com.pingpong.tracker.widget.PingPongWidgetProvider
import com.pingpong.tracker.worker.WidgetUpdateWorker
import java.util.Locale

class MainActivity : Activity() {

    private enum class Tab { MATCHES, CLUBS, BRACKET }
    private enum class MatchFilter(val label: String) {
        ALL("Tous"),
        LIVE("En direct"),
        CLUB("Mes clubs"),
        INTL("WTT / Inter"),
    }

    private lateinit var store: MatchStore
    private lateinit var pinkDustBg: PinkDustBackgroundView

    // Header
    private lateinit var headerSyncStatus: TextView
    private lateinit var btnHeaderRefresh: ImageButton
    private lateinit var btnHeaderSettings: ImageButton
    private lateinit var bannerError: TextView

    // Tab views
    private lateinit var tabMatchesView: View
    private lateinit var tabClubsView: View
    private lateinit var tabBracketView: View

    // Bottom nav
    private lateinit var navMatches: LinearLayout
    private lateinit var navClubs: LinearLayout
    private lateinit var navBracket: LinearLayout
    private lateinit var navMatchesIndicator: View
    private lateinit var navClubsIndicator: View
    private lateinit var navBracketIndicator: View
    private lateinit var navMatchesIcon: ImageView
    private lateinit var navClubsIcon: ImageView
    private lateinit var navBracketIcon: ImageView
    private lateinit var navMatchesLabel: TextView
    private lateinit var navClubsLabel: TextView
    private lateinit var navBracketLabel: TextView

    // Hero card
    private lateinit var heroMatchCard: View
    private lateinit var heroKicker: TextView
    private lateinit var heroVpnBadge: TextView
    private lateinit var heroCountdown: TextView
    private lateinit var heroCrestHome: TextView
    private lateinit var heroHomeName: TextView
    private lateinit var heroCenterScore: TextView
    private lateinit var heroTickingClock: TextView
    private lateinit var heroBroadcast: TextView
    private lateinit var heroCrestAway: TextView
    private lateinit var heroAwayName: TextView
    private lateinit var heroFollowed: TextView

    // Matches tab
    private lateinit var matchesFilterChips: LinearLayout
    private lateinit var matchesEmptyText: TextView
    private lateinit var matchesListContainer: LinearLayout

    // Clubs tab
    private lateinit var clubHubCrest: TextView
    private lateinit var clubHubTitle: TextView
    private lateinit var clubHubSubtitle: TextView
    private lateinit var btnAddClub: View
    private lateinit var clubsChipsContainer: LinearLayout
    private lateinit var clubsSectionTitle: TextView
    private lateinit var clubsEmptyText: TextView
    private lateinit var clubsMatchesContainer: LinearLayout

    // Bracket tab
    private lateinit var tournamentChipsContainer: LinearLayout
    private lateinit var tournamentHeaderCard: View
    private lateinit var tournamentCategory: TextView
    private lateinit var tournamentName: TextView
    private lateinit var tournamentSubtitle: TextView
    private lateinit var bracketColumnsContainer: LinearLayout
    private lateinit var bracketEmptyText: TextView
    private lateinit var intlMatchesHeader: TextView
    private lateinit var intlMatchesContainer: LinearLayout

    private var currentTab: Tab = Tab.MATCHES
    private var currentFilter: MatchFilter = MatchFilter.ALL
    private var selectedClub: String? = null
    private var selectedTournamentId: String? = null
    private var heroMatch: MatchItem? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            updateHeroTickingClock()
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runOnUiThread { renderAll() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        pinkDustBg = findViewById(R.id.pink_dust_bg)

        val rootLayout = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        store = MatchStore(this)
        NotificationHelper.ensureChannel(this)
        WidgetUpdateWorker.schedulePeriodic(this)

        bindViews()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        selectTab(Tab.MATCHES)
        renderAll()
    }

    override fun onStart() {
        super.onStart()
        pinkDustBg.startAnimation()
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        renderAll()
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.post(clockTicker)
    }

    override fun onStop() {
        pinkDustBg.stopAnimation()
        clockHandler.removeCallbacks(clockTicker)
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    private fun bindViews() {
        headerSyncStatus = findViewById(R.id.header_sync_status)
        btnHeaderRefresh = findViewById(R.id.btn_header_refresh)
        btnHeaderSettings = findViewById(R.id.btn_header_settings)
        bannerError = findViewById(R.id.banner_error)

        tabMatchesView = findViewById(R.id.tab_matches_view)
        tabClubsView = findViewById(R.id.tab_clubs_view)
        tabBracketView = findViewById(R.id.tab_bracket_view)

        navMatches = findViewById(R.id.nav_matches)
        navClubs = findViewById(R.id.nav_clubs)
        navBracket = findViewById(R.id.nav_bracket)
        navMatchesIndicator = findViewById(R.id.nav_matches_indicator)
        navClubsIndicator = findViewById(R.id.nav_clubs_indicator)
        navBracketIndicator = findViewById(R.id.nav_bracket_indicator)
        navMatchesIcon = findViewById(R.id.nav_matches_icon)
        navClubsIcon = findViewById(R.id.nav_clubs_icon)
        navBracketIcon = findViewById(R.id.nav_bracket_icon)
        navMatchesLabel = findViewById(R.id.nav_matches_label)
        navClubsLabel = findViewById(R.id.nav_clubs_label)
        navBracketLabel = findViewById(R.id.nav_bracket_label)

        heroMatchCard = findViewById(R.id.hero_match_card)
        heroKicker = findViewById(R.id.hero_kicker)
        heroVpnBadge = findViewById(R.id.hero_vpn_badge)
        heroCountdown = findViewById(R.id.hero_countdown)
        heroCrestHome = findViewById(R.id.hero_crest_home)
        heroHomeName = findViewById(R.id.hero_home_name)
        heroCenterScore = findViewById(R.id.hero_center_score)
        heroTickingClock = findViewById(R.id.hero_ticking_clock)
        heroBroadcast = findViewById(R.id.hero_broadcast)
        heroCrestAway = findViewById(R.id.hero_crest_away)
        heroAwayName = findViewById(R.id.hero_away_name)
        heroFollowed = findViewById(R.id.hero_followed)

        matchesFilterChips = findViewById(R.id.matches_filter_chips)
        matchesEmptyText = findViewById(R.id.matches_empty_text)
        matchesListContainer = findViewById(R.id.matches_list_container)

        clubHubCrest = findViewById(R.id.club_hub_crest)
        clubHubTitle = findViewById(R.id.club_hub_title)
        clubHubSubtitle = findViewById(R.id.club_hub_subtitle)
        btnAddClub = findViewById(R.id.btn_add_club)
        clubsChipsContainer = findViewById(R.id.clubs_chips_container)
        clubsSectionTitle = findViewById(R.id.clubs_section_title)
        clubsEmptyText = findViewById(R.id.clubs_empty_text)
        clubsMatchesContainer = findViewById(R.id.clubs_matches_container)

        tournamentChipsContainer = findViewById(R.id.tournament_chips_container)
        tournamentHeaderCard = findViewById(R.id.tournament_header_card)
        tournamentCategory = findViewById(R.id.tournament_category)
        tournamentName = findViewById(R.id.tournament_name)
        tournamentSubtitle = findViewById(R.id.tournament_subtitle)
        bracketColumnsContainer = findViewById(R.id.bracket_columns_container)
        bracketEmptyText = findViewById(R.id.bracket_empty_text)
        intlMatchesHeader = findViewById(R.id.intl_matches_header)
        intlMatchesContainer = findViewById(R.id.intl_matches_container)
    }

    private fun setupListeners() {
        btnHeaderRefresh.setOnClickListener { triggerManualSync() }
        btnHeaderSettings.setOnClickListener { showSettingsDialog() }

        findViewById<View>(R.id.quick_btn_widget).setOnClickListener { requestPinWidget() }
        findViewById<View>(R.id.quick_btn_notifs).setOnClickListener { showNotificationsDialog() }
        findViewById<View>(R.id.quick_btn_club).setOnClickListener { showAddClubDialog() }

        navMatches.setOnClickListener { selectTab(Tab.MATCHES) }
        navClubs.setOnClickListener { selectTab(Tab.CLUBS) }
        navBracket.setOnClickListener { selectTab(Tab.BRACKET) }

        btnAddClub.setOnClickListener { showAddClubDialog() }
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        tabMatchesView.visibility = if (tab == Tab.MATCHES) View.VISIBLE else View.GONE
        tabClubsView.visibility = if (tab == Tab.CLUBS) View.VISIBLE else View.GONE
        tabBracketView.visibility = if (tab == Tab.BRACKET) View.VISIBLE else View.GONE

        val activeView = when (tab) {
            Tab.MATCHES -> tabMatchesView
            Tab.CLUBS -> tabClubsView
            Tab.BRACKET -> tabBracketView
        }
        activeView.alpha = 0f
        activeView.translationY = dp(12).toFloat()
        activeView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        updateNavItem(navMatchesIndicator, navMatchesIcon, navMatchesLabel, tab == Tab.MATCHES)
        updateNavItem(navClubsIndicator, navClubsIcon, navClubsLabel, tab == Tab.CLUBS)
        updateNavItem(navBracketIndicator, navBracketIcon, navBracketLabel, tab == Tab.BRACKET)
    }

    private fun updateNavItem(indicator: View, icon: ImageView, label: TextView, active: Boolean) {
        indicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
        val color = if (active) 0xFFDB2777.toInt() else 0xFFA08493.toInt()
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun renderAll() {
        val feed = store.getCachedFeed()
        val allMatches = feed?.let { MatchFormatter.sortMatchesForList(it.matches) }.orEmpty()
        val trackedClubs = store.getTrackedClubs()

        val visibleMatches = allMatches.filter { match ->
            !match.isClub || trackedClubs.any { club -> match.matchesClub(club) }
        }

        headerSyncStatus.text = if (store.isSyncing()) {
            "Synchronisation..."
        } else {
            val label = feed?.updatedLabel?.takeIf { it.isNotBlank() }
                ?: MatchFormatter.formatSyncLabel(store.getLastSyncEpochMs())
            "MAJ $label · ${visibleMatches.size} matchs"
        }
        btnHeaderRefresh.isEnabled = !store.isSyncing()
        btnHeaderRefresh.alpha = if (store.isSyncing()) 0.4f else 1f

        val lastError = store.getLastError()
        if (lastError.isNullOrBlank()) {
            bannerError.visibility = View.GONE
        } else {
            bannerError.visibility = View.VISIBLE
            bannerError.text = lastError
        }

        renderMatchesTab(visibleMatches, trackedClubs)
        renderClubsTab(allMatches, trackedClubs)
        renderBracketTab(feed?.tournaments.orEmpty(), allMatches, trackedClubs)
    }

    // region TAB 1: MATCHES + HERO SPOTLIGHT
    private fun renderMatchesTab(visibleMatches: List<MatchItem>, trackedClubs: List<String>) {
        // Spotlight Hero Card (first live or upcoming match)
        val spotlight = visibleMatches.firstOrNull { it.isLive || it.isScheduled }
            ?: visibleMatches.firstOrNull()
        if (spotlight == null) {
            heroMatchCard.visibility = View.GONE
        } else {
            heroMatchCard.visibility = View.VISIBLE
            bindHeroCard(spotlight, trackedClubs)
        }

        matchesFilterChips.removeAllViews()
        for (filter in MatchFilter.entries) {
            matchesFilterChips.addView(
                createPillChip(
                    text = filter.label,
                    active = filter == currentFilter,
                    removable = false,
                    onClick = {
                        currentFilter = filter
                        renderMatchesTab(visibleMatches, trackedClubs)
                    },
                )
            )
        }

        val filteredBase = when (currentFilter) {
            MatchFilter.ALL -> visibleMatches
            MatchFilter.LIVE -> visibleMatches.filter { it.isLive }
            MatchFilter.CLUB -> visibleMatches.filter { it.isClub }
            MatchFilter.INTL -> visibleMatches.filter { it.isInternational }
        }
        // Avoid duplicating the spotlight match right below the Hero card in ALL view
        val filtered = if (currentFilter == MatchFilter.ALL && spotlight != null && filteredBase.size > 1) {
            filteredBase.filterNot { it.id == spotlight.id }
        } else {
            filteredBase
        }

        matchesListContainer.removeAllViews()
        if (filtered.isEmpty()) {
            matchesEmptyText.visibility = View.VISIBLE
            matchesEmptyText.text = if (visibleMatches.isEmpty()) {
                "Aucun match en cache. Vérifiez l'URL du flux dans les réglages puis actualisez."
            } else {
                "Aucun match dans cette catégorie pour le moment."
            }
            return
        }

        matchesEmptyText.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        val liveList = filtered.filter { it.isLive }
        val upcomingList = filtered.filter { it.isScheduled }
        val finishedList = filtered.filter { it.isFinished }

        if (liveList.isNotEmpty()) {
            matchesListContainer.addView(createSectionHeader("EN DIRECT (${liveList.size})", 0xFFE11D48.toInt()))
            for (match in liveList) {
                val card = inflater.inflate(R.layout.item_match, matchesListContainer, false)
                bindMatchCard(card, match, trackedClubs)
                matchesListContainer.addView(card)
            }
        }
        if (upcomingList.isNotEmpty()) {
            matchesListContainer.addView(createSectionHeader("À VENIR (${upcomingList.size})", 0xFFDB2777.toInt()))
            for (match in upcomingList) {
                val card = inflater.inflate(R.layout.item_match, matchesListContainer, false)
                bindMatchCard(card, match, trackedClubs)
                matchesListContainer.addView(card)
            }
        }
        if (finishedList.isNotEmpty()) {
            matchesListContainer.addView(createSectionHeader("TERMINÉS (${finishedList.size})", 0xFF8A6B7B.toInt()))
            for (match in finishedList) {
                val card = inflater.inflate(R.layout.item_match, matchesListContainer, false)
                bindMatchCard(card, match, trackedClubs)
                matchesListContainer.addView(card)
            }
        }
    }

    private fun createSectionHeader(title: String, color: Int): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(color)
            textSize = 10f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }
    }

    private fun bindHeroCard(match: MatchItem, trackedClubs: List<String>) {
        heroMatch = match
        heroKicker.text = if (match.isLive) {
            "EN DIRECT · ${MatchFormatter.formatCompetition(match)}"
        } else {
            "À LA UNE · ${MatchFormatter.formatCompetition(match)}"
        }
        heroCountdown.text = MatchFormatter.formatCountdown(match)

        if (match.vpnRequired) {
            heroVpnBadge.visibility = View.VISIBLE
            heroVpnBadge.text = match.vpnNote.trim().ifEmpty { "VPN" }
        } else {
            heroVpnBadge.visibility = View.GONE
        }

        bindCrest(heroCrestHome, match.home, trackedClubs)
        bindCrest(heroCrestAway, match.away, trackedClubs)
        heroHomeName.text = match.home
        heroAwayName.text = match.away
        heroCenterScore.text = match.score.trim().ifEmpty { "vs" }
        heroBroadcast.text = match.broadcast.trim()
        heroFollowed.text = match.followed.joinToString(" · ")
        updateHeroTickingClock()

        heroMatchCard.setOnClickListener {
            if (match.url.isNotBlank()) openUrl(match.url)
        }
    }

    private fun updateHeroTickingClock() {
        val m = heroMatch ?: return
        if (m.isLive) {
            heroTickingClock.text = "MATCH EN COURS"
            return
        }
        if (m.isFinished) {
            heroTickingClock.text = "TERMINÉ"
            return
        }
        val start = m.startInstant()
        if (start == null) {
            heroTickingClock.text = "À VENIR"
            return
        }
        val diffSec = java.time.Duration.between(java.time.Instant.now(), start).seconds
        if (diffSec <= 0) {
            heroTickingClock.text = "IMMINENT"
            return
        }
        val days = diffSec / 86400
        val hrs = (diffSec % 86400) / 3600
        val mins = (diffSec % 3600) / 60
        val secs = diffSec % 60
        heroTickingClock.text = if (days > 0) {
            String.format(Locale.FRANCE, "J-%d · %02d:%02d:%02d", days, hrs, mins, secs)
        } else {
            String.format(Locale.FRANCE, "%02d:%02d:%02d", hrs, mins, secs)
        }
    }
    // endregion

    // region TAB 2: CLUBS
    private fun renderClubsTab(allMatches: List<MatchItem>, trackedClubs: List<String>) {
        if (selectedClub != null && trackedClubs.none { it.equals(selectedClub, ignoreCase = true) }) {
            selectedClub = null
        }

        clubsChipsContainer.removeAllViews()
        clubsChipsContainer.addView(
            createPillChip(
                text = "Tous (${trackedClubs.size})",
                active = selectedClub == null,
                removable = false,
                onClick = {
                    selectedClub = null
                    renderClubsTab(allMatches, trackedClubs)
                },
            )
        )

        for (club in trackedClubs) {
            val isSelected = selectedClub?.equals(club, ignoreCase = true) == true
            val canRemove = trackedClubs.size > 1
            clubsChipsContainer.addView(
                createPillChip(
                    text = club,
                    active = isSelected,
                    removable = canRemove,
                    onClick = {
                        selectedClub = club
                        renderClubsTab(allMatches, trackedClubs)
                    },
                    onRemove = { confirmRemoveClub(club) },
                )
            )
        }

        val clubMatches = allMatches.filter { match ->
            val targetClubs = selectedClub?.let { listOf(it) } ?: trackedClubs
            targetClubs.any { club -> match.matchesClub(club) }
        }

        // Update Club Hub Banner
        val activeClubName = selectedClub ?: trackedClubs.firstOrNull() ?: "CTT Bruille"
        bindCrest(clubHubCrest, activeClubName, trackedClubs)
        clubHubTitle.text = if (selectedClub != null) activeClubName else "Mes clubs Pro A"
        val upcomingCount = clubMatches.count { !it.isFinished }
        val matchWord = if (upcomingCount <= 1) "match à venir" else "matchs à venir"
        clubHubSubtitle.text = "${trackedClubs.size} club(s) suivi(s) · $upcomingCount $matchWord"

        clubsSectionTitle.text = if (selectedClub != null) {
            "CALENDRIER · ${selectedClub!!.uppercase(Locale.FRANCE)}"
        } else {
            "CALENDRIER DE MES CLUBS"
        }

        clubsMatchesContainer.removeAllViews()
        if (clubMatches.isEmpty()) {
            clubsEmptyText.visibility = View.VISIBLE
            return
        }

        clubsEmptyText.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (match in clubMatches) {
            val card = inflater.inflate(R.layout.item_match, clubsMatchesContainer, false)
            bindMatchCard(card, match, trackedClubs)
            clubsMatchesContainer.addView(card)
        }
    }

    private fun showAddClubDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_club, null)
        val input = view.findViewById<EditText>(R.id.add_club_input)
        val btnConfirm = view.findViewById<Button>(R.id.add_club_confirm)
        val suggestionsContainer = view.findViewById<LinearLayout>(R.id.add_club_suggestions)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val tracked = store.getTrackedClubs()
        for (club in MatchStore.SUGGESTED_CLUBS) {
            val alreadyAdded = tracked.any { it.equals(club, ignoreCase = true) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_surface_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) }
            }
            val label = TextView(this).apply {
                text = club
                setTextColor(if (alreadyAdded) 0xFFA08493.toInt() else 0xFF1E1118.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badge = TextView(this).apply {
                text = if (alreadyAdded) "Suivi" else "+ Suivre"
                setTextColor(if (alreadyAdded) 0xFF059669.toInt() else 0xFFDB2777.toInt())
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(label)
            row.addView(badge)

            if (!alreadyAdded) {
                row.setOnClickListener {
                    store.addTrackedClub(club)
                    selectedClub = club
                    PingPongWidgetProvider.updateAllWidgets(this)
                    renderAll()
                    dialog.dismiss()
                }
            }
            suggestionsContainer.addView(row)
        }

        btnConfirm.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.length < 2) {
                Toast.makeText(this, "Nom de club trop court.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (store.addTrackedClub(name)) {
                selectedClub = name
                PingPongWidgetProvider.updateAllWidgets(this)
                renderAll()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Ce club est déjà suivi.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun confirmRemoveClub(club: String) {
        AlertDialog.Builder(this)
            .setTitle("Retirer $club ?")
            .setMessage("Ce club ne sera plus affiché dans vos matchs, alertes ni dans le widget.")
            .setPositiveButton("Retirer") { _, _ ->
                store.removeTrackedClub(club)
                PingPongWidgetProvider.updateAllWidgets(this)
                renderAll()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
    // endregion

    // region TAB 3: INTERNATIONAL BRACKET
    private fun renderBracketTab(
        tournaments: List<TournamentBracket>,
        allMatches: List<MatchItem>,
        trackedClubs: List<String>,
    ) {
        tournamentChipsContainer.removeAllViews()
        bracketColumnsContainer.removeAllViews()

        if (tournaments.isEmpty()) {
            tournamentHeaderCard.visibility = View.GONE
            bracketEmptyText.visibility = View.VISIBLE
        } else {
            bracketEmptyText.visibility = View.GONE
            tournamentHeaderCard.visibility = View.VISIBLE

            val currentTournament = tournaments.firstOrNull { it.id == selectedTournamentId }
                ?: tournaments.first()
            selectedTournamentId = currentTournament.id

            for (t in tournaments) {
                tournamentChipsContainer.addView(
                    createPillChip(
                        text = t.name,
                        active = t.id == currentTournament.id,
                        removable = false,
                        onClick = {
                            selectedTournamentId = t.id
                            renderBracketTab(tournaments, allMatches, trackedClubs)
                        },
                    )
                )
            }

            tournamentCategory.text = currentTournament.category.uppercase(Locale.FRANCE)
            tournamentName.text = currentTournament.name
            tournamentSubtitle.text = listOf(currentTournament.location, currentTournament.dates)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")

            renderBracketTree(currentTournament)
        }

        val intlMatches = allMatches.filter { it.isInternational }
        intlMatchesContainer.removeAllViews()
        if (intlMatches.isEmpty()) {
            intlMatchesHeader.visibility = View.GONE
        } else {
            intlMatchesHeader.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(this)
            for (match in intlMatches) {
                val card = inflater.inflate(R.layout.item_match, intlMatchesContainer, false)
                bindMatchCard(card, match, trackedClubs)
                intlMatchesContainer.addView(card)
            }
        }
    }

    private fun renderBracketTree(tournament: TournamentBracket) {
        val inflater = LayoutInflater.from(this)
        val baseSlotDp = 98

        tournament.rounds.forEachIndexed { roundIndex, round ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(14)
                }
            }

            val header = TextView(this).apply {
                text = round.name.uppercase(Locale.FRANCE)
                setTextColor(0xFFDB2777.toInt())
                textSize = 10f
                letterSpacing = 0.08f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(2), 0, 0, dp(10))
            }
            col.addView(header)

            val slotHeightPx = dp(baseSlotDp * (1 shl roundIndex.coerceAtMost(3)))

            for (bm in round.matches) {
                val slot = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        slotHeightPx,
                    )
                }

                val card = inflater.inflate(R.layout.item_bracket_match, slot, false)
                bindBracketMatchCard(card, bm)
                slot.addView(card)

                if (roundIndex < tournament.rounds.lastIndex) {
                    val connector = View(this).apply {
                        setBackgroundColor(0x55F472B6.toInt())
                        layoutParams = LinearLayout.LayoutParams(dp(12), dp(1))
                    }
                    slot.addView(connector)
                }

                col.addView(slot)
            }

            bracketColumnsContainer.addView(col)
        }
    }

    private fun bindBracketMatchCard(card: View, bm: BracketMatch) {
        val tagsRow = card.findViewById<View>(R.id.bm_tags_row)
        val liveTag = card.findViewById<TextView>(R.id.bm_live_tag)
        val vpnTag = card.findViewById<TextView>(R.id.bm_vpn_tag)
        val dot1 = card.findViewById<View>(R.id.bm_dot_1)
        val dot2 = card.findViewById<View>(R.id.bm_dot_2)
        val country1 = card.findViewById<TextView>(R.id.bm_country_1)
        val country2 = card.findViewById<TextView>(R.id.bm_country_2)
        val player1 = card.findViewById<TextView>(R.id.bm_player_1)
        val player2 = card.findViewById<TextView>(R.id.bm_player_2)
        val score1 = card.findViewById<TextView>(R.id.bm_score_1)
        val score2 = card.findViewById<TextView>(R.id.bm_score_2)

        tagsRow.visibility = if (bm.isLive || bm.vpnRequired) View.VISIBLE else View.GONE
        liveTag.visibility = if (bm.isLive) View.VISIBLE else View.GONE
        vpnTag.visibility = if (bm.vpnRequired) View.VISIBLE else View.GONE
        dot1.visibility = if (bm.highlight1) View.VISIBLE else View.INVISIBLE
        dot2.visibility = if (bm.highlight2) View.VISIBLE else View.INVISIBLE

        country1.text = bm.country1
        country2.text = bm.country2
        player1.text = bm.player1
        player2.text = bm.player2
        score1.text = bm.score1.ifBlank { "-" }
        score2.text = bm.score2.ifBlank { "-" }

        val p1Bold = bm.winner == 1 || bm.highlight1
        val p2Bold = bm.winner == 2 || bm.highlight2
        player1.setTypeface(null, if (p1Bold) Typeface.BOLD else Typeface.NORMAL)
        player2.setTypeface(null, if (p2Bold) Typeface.BOLD else Typeface.NORMAL)

        val p1Color = when {
            bm.winner == 2 -> 0xFFA08493.toInt()
            bm.highlight1 -> 0xFFDB2777.toInt()
            else -> 0xFF1E1118.toInt()
        }
        val p2Color = when {
            bm.winner == 1 -> 0xFFA08493.toInt()
            bm.highlight2 -> 0xFFDB2777.toInt()
            else -> 0xFF4A303E.toInt()
        }
        player1.setTextColor(p1Color)
        score1.setTextColor(if (bm.winner == 1) 0xFF059669.toInt() else p1Color)
        player2.setTextColor(p2Color)
        score2.setTextColor(if (bm.winner == 2) 0xFF059669.toInt() else p2Color)

        card.setOnClickListener {
            if (bm.url.isNotBlank()) openUrl(bm.url)
        }
    }
    // endregion

    // region SHARED CARD, CREST & CHIP HELPERS
    private fun bindMatchCard(card: View, match: MatchItem, trackedClubs: List<String>) {
        val accentBar = card.findViewById<View>(R.id.item_accent_bar)
        val compView = card.findViewById<TextView>(R.id.item_comp)
        val countdownView = card.findViewById<TextView>(R.id.item_countdown)
        val crestHome = card.findViewById<TextView>(R.id.item_crest_home)
        val crestAway = card.findViewById<TextView>(R.id.item_crest_away)
        val homeView = card.findViewById<TextView>(R.id.item_home)
        val awayView = card.findViewById<TextView>(R.id.item_away)
        val scoreHomeView = card.findViewById<TextView>(R.id.item_score_home)
        val scoreAwayView = card.findViewById<TextView>(R.id.item_score_away)
        val metaView = card.findViewById<TextView>(R.id.item_meta)
        val vpnBadge = card.findViewById<TextView>(R.id.item_vpn_badge)

        val badgeType = MatchFormatter.badgeType(match, trackedClubs)
        accentBar.setBackgroundColor(MatchFormatter.badgeColorHex(badgeType))

        compView.text = MatchFormatter.formatCompetition(match)
        countdownView.text = MatchFormatter.formatCountdown(match)

        if (match.isLive) {
            countdownView.setBackgroundResource(R.drawable.bg_live_pill)
            countdownView.setTextColor(0xFFBE123C.toInt())
        } else {
            countdownView.background = null
            countdownView.setTextColor(
                if (match.isFinished) 0xFFA08493.toInt() else 0xFFDB2777.toInt(),
            )
        }

        bindCrest(crestHome, match.home, trackedClubs)
        bindCrest(crestAway, match.away, trackedClubs)
        homeView.text = match.home
        awayView.text = match.away

        val scorePair = MatchFormatter.parseScorePair(match.score)
        if (scorePair != null) {
            scoreHomeView.visibility = View.VISIBLE
            scoreAwayView.visibility = View.VISIBLE
            scoreHomeView.text = scorePair.first
            scoreAwayView.text = scorePair.second
        } else if (match.score.isNotBlank()) {
            scoreHomeView.visibility = View.VISIBLE
            scoreAwayView.visibility = View.GONE
            scoreHomeView.text = match.score
        } else {
            scoreHomeView.visibility = View.GONE
            scoreAwayView.visibility = View.GONE
        }

        val meta = listOf(match.followed.joinToString(" · "), match.broadcast.trim())
            .filter { it.isNotEmpty() }
            .joinToString("  ·  ")
        if (meta.isBlank()) {
            metaView.visibility = View.GONE
        } else {
            metaView.visibility = View.VISIBLE
            metaView.text = meta
        }

        if (match.vpnRequired) {
            vpnBadge.visibility = View.VISIBLE
            vpnBadge.text = match.vpnNote.trim().ifEmpty { "VPN conseillé" }
        } else {
            vpnBadge.visibility = View.GONE
        }

        card.setOnClickListener {
            if (match.url.isNotBlank()) openUrl(match.url)
        }
    }

    private fun bindCrest(view: TextView, name: String, trackedClubs: List<String>) {
        view.text = monogramFor(name)
        val lowered = name.lowercase(Locale.FRANCE)
        when {
            lowered.contains("bruille") ||
                trackedClubs.any { lowered.contains(it.lowercase(Locale.FRANCE)) } -> {
                view.setBackgroundResource(R.drawable.bg_crest_bruille)
                view.setTextColor(0xFF047857.toInt())
            }
            lowered.contains("lebrun") ||
                lowered.contains("coton") ||
                lowered.contains("poret") ||
                lowered.contains("gauzy") -> {
                view.setBackgroundResource(R.drawable.bg_crest_france)
                view.setTextColor(0xFFBE185D.toInt())
            }
            else -> {
                view.setBackgroundResource(R.drawable.bg_crest_neutral)
                view.setTextColor(0xFF9D174D.toInt())
            }
        }
    }

    private fun monogramFor(name: String): String {
        val lower = name.lowercase(Locale.FRANCE)
        if (lower.contains("félix") && lower.contains("alexis")) return "LB"
        if (lower.contains("top 16") || lower.contains("tirage")) return "WT"

        val cleaned = name.trim()
            .replace("CTT ", "", ignoreCase = true)
            .replace("SPO ", "", ignoreCase = true)
            .replace("AS ", "", ignoreCase = true)
            .replace("GV ", "", ignoreCase = true)
            .replace("SS ", "", ignoreCase = true)
            .replace("Alliance ", "", ignoreCase = true)
            .replace("Les Loups d'", "", ignoreCase = true)
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .trim()
        val words = cleaned.split(Regex("\\s+")).filter { it.length >= 2 }
        return when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase(Locale.FRANCE)
            words.size == 1 && words[0].length >= 2 -> words[0].substring(0, 2).uppercase(Locale.FRANCE)
            else -> "TT"
        }
    }

    private fun createPillChip(
        text: String,
        active: Boolean,
        removable: Boolean,
        onClick: () -> Unit,
        onRemove: (() -> Unit)? = null,
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
            setPadding(dp(14), dp(7), dp(if (removable) 8 else 14), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }

        val label = TextView(this).apply {
            this.text = text
            setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF7A5C6B.toInt())
            textSize = 12f
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        container.addView(label)

        if (removable && onRemove != null) {
            val closeIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(if (active) 0xFFFFFFFF.toInt() else 0xFFA08493.toInt())
                setPadding(dp(6), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(18))
                setOnClickListener { onRemove() }
            }
            container.addView(closeIcon)
        }

        return container
    }
    // endregion

    // region NOTIFICATIONS & SETTINGS DIALOGS
    private fun showNotificationsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_notifications, null)
        val switch30m = view.findViewById<Switch>(R.id.switch_notif_30m)
        val switchLive = view.findViewById<Switch>(R.id.switch_notif_live)
        val switchClubs = view.findViewById<Switch>(R.id.switch_notif_clubs)
        val switchIntl = view.findViewById<Switch>(R.id.switch_notif_intl)
        val switchVpn = view.findViewById<Switch>(R.id.switch_notif_vpn)
        val btnTestNotif = view.findViewById<Button>(R.id.settings_btn_test_notif)

        switch30m.isChecked = store.isNotif30mEnabled()
        switchLive.isChecked = store.isNotifLiveEnabled()
        switchClubs.isChecked = store.isNotifClubsEnabled()
        switchIntl.isChecked = store.isNotifIntlEnabled()
        switchVpn.isChecked = store.isNotifVpnHintEnabled()

        switch30m.setOnCheckedChangeListener { _, checked -> store.setNotif30mEnabled(checked) }
        switchLive.setOnCheckedChangeListener { _, checked -> store.setNotifLiveEnabled(checked) }
        switchClubs.setOnCheckedChangeListener { _, checked -> store.setNotifClubsEnabled(checked) }
        switchIntl.setOnCheckedChangeListener { _, checked -> store.setNotifIntlEnabled(checked) }
        switchVpn.setOnCheckedChangeListener { _, checked -> store.setNotifVpnHintEnabled(checked) }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnTestNotif.setOnClickListener {
            val sent = NotificationHelper.sendTestNotification(this, store)
            if (sent) {
                Toast.makeText(this, "Notification de test envoyée !", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Autorisez d'abord les notifications Android.",
                    Toast.LENGTH_SHORT,
                ).show()
                requestNotificationPermissionIfNeeded()
            }
        }

        dialog.show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val statusText = view.findViewById<TextView>(R.id.settings_status)
        val inputUrl = view.findViewById<EditText>(R.id.settings_input_url)
        val btnSave = view.findViewById<Button>(R.id.settings_btn_save_url)
        val btnPin = view.findViewById<Button>(R.id.settings_btn_pin_widget)
        val btnBattery = view.findViewById<Button>(R.id.settings_btn_battery)

        inputUrl.setText(store.getFeedUrl())
        val lastSync = MatchFormatter.formatSyncLabel(store.getLastSyncEpochMs())
        val err = store.getLastError()
        statusText.text = if (err.isNullOrBlank()) {
            "Dernière synchro : $lastSync"
        } else {
            "Dernière synchro : $lastSync · $err"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnSave.setOnClickListener {
            val newUrl = inputUrl.text.toString().trim()
            if (newUrl.isEmpty() || (!newUrl.startsWith("http://") && !newUrl.startsWith("https://"))) {
                Toast.makeText(
                    this,
                    "Veuillez saisir une URL http:// ou https:// valide.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            store.setFeedUrl(newUrl)
            triggerManualSync()
            dialog.dismiss()
        }

        btnPin.setOnClickListener {
            dialog.dismiss()
            requestPinWidget()
        }

        btnBattery.setOnClickListener {
            dialog.dismiss()
            openBatterySettings()
        }

        dialog.show()
    }

    private fun triggerManualSync() {
        btnHeaderRefresh.animate().rotationBy(360f).setDuration(600L).start()
        store.setSyncing(true)
        PingPongWidgetProvider.updateAllWidgets(this)
        WidgetUpdateWorker.enqueueImmediateSync(this, isManual = true)
        renderAll()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir le lien.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPinWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, PingPongWidgetProvider::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val accepted = appWidgetManager.requestPinAppWidget(provider, null, null)
            if (!accepted) showManualPinHelpDialog()
        } else {
            showManualPinHelpDialog()
        }
    }

    private fun showManualPinHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ajouter le widget manuellement")
            .setMessage(
                "Votre écran d'accueil ne supporte pas l'épinglage automatique.\n\n" +
                    "1. Appuyez longuement sur l'écran d'accueil.\n" +
                    "2. Sélectionnez « Widgets ».\n" +
                    "3. Ajoutez « PingPong Tracker »."
            )
            .setPositiveButton("Compris", null)
            .show()
    }

    private fun openBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // fallback
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS,
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifications désactivées : les rappels H-30 et Live ne s'afficheront pas.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    // endregion

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
    }
}
