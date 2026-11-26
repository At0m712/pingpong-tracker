package com.pingpong.tracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.pingpong.tracker.MainActivity
import com.pingpong.tracker.R
import com.pingpong.tracker.data.BadgeType
import com.pingpong.tracker.data.MatchFormatter
import com.pingpong.tracker.data.MatchItem
import com.pingpong.tracker.data.MatchStore
import com.pingpong.tracker.worker.WidgetUpdateWorker

class PingPongWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateWorker.schedulePeriodic(context)
        WidgetUpdateWorker.enqueueImmediateSync(context, isManual = false)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdateWorker.schedulePeriodic(context)
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_MANUAL_REFRESH) {
            val store = MatchStore(context)
            store.setSyncing(true)
            updateAllWidgets(context)
            WidgetUpdateWorker.enqueueImmediateSync(context, isManual = true)
        }
    }

    companion object {
        const val ACTION_MANUAL_REFRESH = "com.pingpong.tracker.ACTION_MANUAL_REFRESH"

        private data class CardSlot(
            val containerId: Int,
            val compId: Int,
            val countdownId: Int,
            val titleId: Int,
            val metaId: Int,
        )

        private val CARD_SLOTS = listOf(
            CardSlot(R.id.card_1, R.id.card_1_comp, R.id.card_1_countdown, R.id.card_1_title, R.id.card_1_meta),
            CardSlot(R.id.card_2, R.id.card_2_comp, R.id.card_2_countdown, R.id.card_2_title, R.id.card_2_meta),
            CardSlot(R.id.card_3, R.id.card_3_comp, R.id.card_3_countdown, R.id.card_3_title, R.id.card_3_meta),
        )

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, PingPongWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            for (id in ids) {
                updateSingleWidget(context, appWidgetManager, id)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val store = MatchStore(context)
            val views = RemoteViews(context.packageName, R.layout.widget_pingpong)

            // Header click opens MainActivity
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                100,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, openAppPendingIntent)

            // Refresh button click triggers manual sync
            val refreshIntent = Intent(context, PingPongWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                101,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent)

            // Sync label
            val syncLabel = if (store.isSyncing()) {
                context.getString(R.string.widget_syncing)
            } else {
                "MAJ ${MatchFormatter.formatSyncLabel(store.getLastSyncEpochMs())}"
            }
            views.setTextViewText(R.id.widget_sync_time, syncLabel)

            // Matches from cache
            val trackedClubs = store.getTrackedClubs()
            val cachedFeed = store.getCachedFeed()
            val selectedMatches = cachedFeed?.let {
                MatchFormatter.selectWidgetMatches(
                    matches = it.matches,
                    trackedClubs = trackedClubs,
                    maxCount = CARD_SLOTS.size,
                )
            }.orEmpty()

            if (selectedMatches.isEmpty()) {
                for (slot in CARD_SLOTS) {
                    views.setViewVisibility(slot.containerId, View.GONE)
                }
                views.setViewVisibility(R.id.widget_empty_container, View.VISIBLE)
                val emptyMessage = when {
                    store.isSyncing() -> "Synchronisation en cours..."
                    !store.getLastError().isNullOrBlank() ->
                        context.getString(R.string.widget_empty_offline)
                    else -> context.getString(R.string.widget_empty_no_matches)
                }
                views.setTextViewText(R.id.widget_empty_text, emptyMessage)
                views.setOnClickPendingIntent(R.id.widget_empty_container, openAppPendingIntent)
            } else {
                views.setViewVisibility(R.id.widget_empty_container, View.GONE)
                CARD_SLOTS.forEachIndexed { index, slot ->
                    val match = selectedMatches.getOrNull(index)
                    if (match == null) {
                        views.setViewVisibility(slot.containerId, View.GONE)
                    } else {
                        bindCard(context, views, slot, match, requestCode = 200 + index)
                    }
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun bindCard(
            context: Context,
            views: RemoteViews,
            slot: CardSlot,
            match: MatchItem,
            requestCode: Int,
        ) {
            views.setViewVisibility(slot.containerId, View.VISIBLE)

            val badgeType = MatchFormatter.badgeType(match)
            val badgeDrawable = when (badgeType) {
                BadgeType.LIVE -> R.drawable.bg_badge_live
                BadgeType.BRUILLE -> R.drawable.bg_badge_bruille
                BadgeType.CLUB -> R.drawable.bg_badge_club
                BadgeType.INTL -> R.drawable.bg_badge_intl
            }
            val badgeTextColor = when (badgeType) {
                BadgeType.INTL -> 0xFF0B1120.toInt()
                else -> 0xFFFFFFFF.toInt()
            }

            views.setTextViewText(slot.compId, MatchFormatter.formatCompetition(match))
            views.setInt(slot.compId, "setBackgroundResource", badgeDrawable)
            views.setTextColor(slot.compId, badgeTextColor)

            val countdown = MatchFormatter.formatCountdown(match)
            views.setTextViewText(slot.countdownId, countdown)
            views.setTextColor(
                slot.countdownId,
                if (match.isLive) 0xFFEF4444.toInt() else 0xFFE2E8F0.toInt(),
            )

            views.setTextViewText(slot.titleId, MatchFormatter.formatMatchTitle(match))

            val meta = MatchFormatter.formatMetaLine(match)
            if (meta.isBlank()) {
                views.setViewVisibility(slot.metaId, View.GONE)
            } else {
                views.setViewVisibility(slot.metaId, View.VISIBLE)
                views.setTextViewText(slot.metaId, meta)
            }

            // Clicking the card opens match.url directly in browser / YouTube
            val targetIntent = if (match.url.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse(match.url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                targetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(slot.containerId, pendingIntent)
        }
    }
}
