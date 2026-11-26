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
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pingpong.tracker.data.BadgeType
import com.pingpong.tracker.data.BracketMatch
import com.pingpong.tracker.data.MatchFormatter
import com.pingpong.tracker.data.MatchItem
import com.pingpong.tracker.data.MatchStore
import com.pingpong.tracker.data.TournamentBracket
import com.pingpong.tracker.notifications.NotificationHelper
import com.pingpong.tracker.widget.PingPongWidgetProvider
import com.pingpong.tracker.worker.WidgetUpdateWorker

class MainActivity : Activity() {

    private enum class Tab { MATCHES, CLUBS, BRACKET }
    private enum class MatchFilter(val label: String) {
        ALL("Tous"),
        LIVE("En direct"),
        CLUB("Pro A"),
        INTL("WTT / Inter"),
    }

    private lateinit var store: MatchStore

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
    private lateinit var navMatchesIcon: ImageView
    private lateinit var navClubsIcon: ImageView
    private lateinit var navBracketIcon: ImageView
    private lateinit var navMatchesLabel: TextView
    private lateinit var navClubsLabel: TextView
    private lateinit var navBracketLabel: TextView

    // Matches tab
    private lateinit var matchesFilterChips: LinearLayout
    private lateinit var matchesEmptyText: TextView
    private lateinit var matchesListContainer: LinearLayout

    // Clubs tab
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
    private var selectedClub: String? = null // null = all followed clubs
    private var selectedTournamentId: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runOnUiThread { renderAll() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        renderAll()
    }

    override fun onStop() {
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
        navMatchesIcon = findViewById(R.id.nav_matches_icon)
        navClubsIcon = findViewById(R.id.nav_clubs_icon)
        navBracketIcon = findViewById(R.id.nav_bracket_icon)
        navMatchesLabel = findViewById(R.id.nav_matches_label)
        navClubsLabel = findViewById(R.id.nav_clubs_label)
        navBracketLabel = findViewById(R.id.nav_bracket_label)

        matchesFilterChips = findViewById(R.id.matches_filter_chips)
        matchesEmptyText = findViewById(R.id.matches_empty_text)
        matchesListContainer = findViewById(R.id.matches_list_container)

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

        updateNavItem(navMatchesIcon, navMatchesLabel, tab == Tab.MATCHES)
        updateNavItem(navClubsIcon, navClubsLabel, tab == Tab.CLUBS)
        updateNavItem(navBracketIcon, navBracketLabel, tab == Tab.BRACKET)
    }

    private fun updateNavItem(icon: ImageView, label: TextView, active: Boolean) {
        val color = if (active) 0xFFFAFAFA.toInt() else 0xFF71717A.toInt()
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun renderAll() {
        val feed = store.getCachedFeed()
        val allMatches = feed?.let { MatchFormatter.sortMatchesForList(it.matches) }.orEmpty()
        val trackedClubs = store.getTrackedClubs()

        // Header sync status
        headerSyncStatus.text = if (store.isSyncing()) {
            "Synchronisation..."
        } else {
            val label = feed?.updatedLabel?.takeIf { it.isNotBlank() }
                ?: MatchFormatter.formatSyncLabel(store.getLastSyncEpochMs())
            "MAJ $label · ${allMatches.size} matchs"
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

        renderMatchesTab(allMatches, trackedClubs)
        renderClubsTab(allMatches, trackedClubs)
        renderBracketTab(feed?.tournaments.orEmpty(), allMatches, trackedClubs)
    }

    // region TAB 1: MATCHES
    private fun renderMatchesTab(allMatches: List<MatchItem>, trackedClubs: List<String>) {
        matchesFilterChips.removeAllViews()
        for (filter in MatchFilter.entries) {
            val chip = createPillChip(
                text = filter.label,
                active = filter == currentFilter,
                removable = false,
                onClick = {
                    currentFilter = filter
                    renderMatchesTab(allMatches, trackedClubs)
                },
            )
            matchesFilterChips.addView(chip)
        }

        val filtered = when (currentFilter) {
            MatchFilter.ALL -> allMatches
            MatchFilter.LIVE -> allMatches.filter { it.isLive }
            MatchFilter.CLUB -> allMatches.filter { it.isClub }
            MatchFilter.INTL -> allMatches.filter { it.isInternational }
        }

        matchesListContainer.removeAllViews()
        if (filtered.isEmpty()) {
            matchesEmptyText.visibility = View.VISIBLE
            matchesEmptyText.text = if (allMatches.isEmpty()) {
                "Aucun match en cache. Vérifiez l'URL du flux dans les réglages puis actualisez."
            } else {
                "Aucun match dans cette catégorie pour le moment."
            }
            return
        }

        matchesEmptyText.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (match in filtered) {
            val card = inflater.inflate(R.layout.item_match, matchesListContainer, false)
            bindMatchCard(card, match, trackedClubs)
            matchesListContainer.addView(card)
        }
    }
    // endregion

    // region TAB 2: CLUBS
    private fun renderClubsTab(allMatches: List<MatchItem>, trackedClubs: List<String>) {
        // Reset selection if removed
        if (selectedClub != null && trackedClubs.none { it.equals(selectedClub, ignoreCase = true) }) {
            selectedClub = null
        }

        clubsChipsContainer.removeAllViews()

        // "Tous mes clubs" chip
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
                    onRemove = {
                        confirmRemoveClub(club)
                    },
                )
            )
        }

        val clubMatches = allMatches.filter { match ->
            val targetClubs = selectedClub?.let { listOf(it) } ?: trackedClubs
            targetClubs.any { club -> match.matchesClub(club) }
        }

        clubsSectionTitle.text = if (selectedClub != null) {
            "MATCHS · ${selectedClub!!.uppercase()}"
        } else {
            "MATCHS DE MES CLUBS"
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
                setTextColor(if (alreadyAdded) 0xFF71717A.toInt() else 0xFFFAFAFA.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badge = TextView(this).apply {
                text = if (alreadyAdded) "Suivi" else "+ Suivre"
                setTextColor(if (alreadyAdded) 0xFF22C55E.toInt() else 0xFFE11D48.toInt())
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
            .setMessage("Ce club ne sera plus affiché dans vos filtres ni dans le widget.")
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

            tournamentCategory.text = currentTournament.category.uppercase()
            tournamentName.text = currentTournament.name
            tournamentSubtitle.text = listOf(currentTournament.location, currentTournament.dates)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")

            renderBracketTree(currentTournament)
        }

        // International matches list below
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
        val baseSlotDp = 96

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

            // Round header
            val header = TextView(this).apply {
                text = round.name.uppercase()
                setTextColor(0xFF71717A.toInt())
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

                // Subtle connector line to next round
                if (roundIndex < tournament.rounds.lastIndex) {
                    val connector = View(this).apply {
                        setBackgroundColor(0xFF27272A.toInt())
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

        // Highlight winner vs loser
        val p1Bold = bm.winner == 1 || bm.highlight1
        val p2Bold = bm.winner == 2 || bm.highlight2
        player1.setTypeface(null, if (p1Bold) Typeface.BOLD else Typeface.NORMAL)
        player2.setTypeface(null, if (p2Bold) Typeface.BOLD else Typeface.NORMAL)

        val p1Color = when {
            bm.winner == 2 -> 0xFF71717A.toInt()
            bm.highlight1 -> 0xFFFAFAFA.toInt()
            else -> 0xFFE4E4E7.toInt()
        }
        val p2Color = when {
            bm.winner == 1 -> 0xFF71717A.toInt()
            bm.highlight2 -> 0xFFFAFAFA.toInt()
            else -> 0xFFE4E4E7.toInt()
        }
        player1.setTextColor(p1Color)
        score1.setTextColor(if (bm.winner == 1) 0xFF22C55E.toInt() else p1Color)
        player2.setTextColor(p2Color)
        score2.setTextColor(if (bm.winner == 2) 0xFF22C55E.toInt() else p2Color)

        card.setOnClickListener {
            if (bm.url.isNotBlank()) {
                openUrl(bm.url)
            }
        }
    }
    // endregion

    // region SHARED CARD & CHIP HELPERS
    private fun bindMatchCard(card: View, match: MatchItem, trackedClubs: List<String>) {
        val accentBar = card.findViewById<View>(R.id.item_accent_bar)
        val compView = card.findViewById<TextView>(R.id.item_comp)
        val countdownView = card.findViewById<TextView>(R.id.item_countdown)
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
            countdownView.setTextColor(0xFFFB7185.toInt())
        } else {
            countdownView.background = null
            countdownView.setTextColor(
                if (match.isFinished) 0xFF71717A.toInt() else 0xFFE4E4E7.toInt(),
            )
        }

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
            if (match.url.isNotBlank()) {
                openUrl(match.url)
            }
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
            setTextColor(if (active) 0xFF09090B.toInt() else 0xFFA1A1AA.toInt())
            textSize = 12f
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        container.addView(label)

        if (removable && onRemove != null) {
            val closeIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(if (active) 0xFF09090B.toInt() else 0xFF71717A.toInt())
                setPadding(dp(6), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(18))
                setOnClickListener { onRemove() }
            }
            container.addView(closeIcon)
        }

        return container
    }
    // endregion

    // region SETTINGS DIALOG & SYSTEM ACTIONS
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
            if (!accepted) {
                showManualPinHelpDialog()
            }
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
