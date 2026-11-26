package com.pingpong.tracker.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pingpong.tracker.MainActivity
import com.pingpong.tracker.R
import com.pingpong.tracker.data.BadgeType
import com.pingpong.tracker.data.MatchFormatter
import com.pingpong.tracker.data.MatchItem
import com.pingpong.tracker.data.MatchStore
import java.time.Duration
import java.time.Instant

object NotificationHelper {

    const val CHANNEL_ID = "pingpong_match_alerts"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 120L, 80L, 160L)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun evaluateAndNotify(
        context: Context,
        matches: List<MatchItem>,
        store: MatchStore,
        now: Instant = Instant.now(),
    ) {
        store.cleanupOldNotifications(now.toEpochMilli())

        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val trackedClubs = store.getTrackedClubs()
        val allow30m = store.isNotif30mEnabled()
        val allowLive = store.isNotifLiveEnabled()
        val allowClubs = store.isNotifClubsEnabled()
        val allowIntl = store.isNotifIntlEnabled()
        val showVpnHint = store.isNotifVpnHintEnabled()

        for (match in matches) {
            if (match.isFinished) continue
            if (match.isClub) {
                if (!allowClubs) continue
                if (trackedClubs.none { club -> match.matchesClub(club) }) continue
            } else if (!allowIntl) {
                continue
            }

            val startInstant = match.startInstant()
            val minutesUntil = startInstant?.let { Duration.between(now, it).toMinutes() }
            val accentColor = MatchFormatter.badgeColorHex(
                MatchFormatter.badgeType(match, trackedClubs),
            )

            // Trigger 1: H-30 min (window 20..40 min before start_iso)
            if (allow30m && match.isScheduled && minutesUntil != null && minutesUntil in 20L..40L) {
                val key30m = MatchStore.key30m(match.id)
                if (!store.hasNotified(key30m)) {
                    val title = "Dans $minutesUntil min · ${match.home} vs ${match.away}"
                    val body = buildNotificationBody(match, showVpnHint)
                    sendMatchNotification(
                        context = context,
                        notificationId = key30m.hashCode(),
                        title = title,
                        body = body,
                        subText = match.competition,
                        accentColor = accentColor,
                        url = match.url,
                        vpnRequired = match.vpnRequired && showVpnHint,
                    )
                    store.markNotified(key30m, now.toEpochMilli())
                }
            }

            // Trigger 2: Coup d'envoi / Imminent (0..15 min before start_iso OR status == "live")
            val isImminent = match.isScheduled && minutesUntil != null && minutesUntil in 0L..15L
            if (allowLive && (match.isLive || isImminent)) {
                val keyLive = MatchStore.keyLive(match.id)
                if (!store.hasNotified(keyLive)) {
                    val prefix = if (match.isLive) "EN DIRECT" else "Coup d'envoi"
                    val title = "$prefix · ${MatchFormatter.formatMatchTitle(match)}"
                    val body = buildNotificationBody(match, showVpnHint)
                    sendMatchNotification(
                        context = context,
                        notificationId = keyLive.hashCode(),
                        title = title,
                        body = body,
                        subText = match.competition,
                        accentColor = if (match.isLive) 0xFFE11D48.toInt() else accentColor,
                        url = match.url,
                        vpnRequired = match.vpnRequired && showVpnHint,
                    )
                    store.markNotified(keyLive, now.toEpochMilli())
                }
            }
        }
    }

    fun sendTestNotification(context: Context, store: MatchStore): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureChannel(context)

        val primaryClub = store.getTrackedClubs().firstOrNull() ?: "CTT Bruille"
        val vpnHint = if (store.isNotifVpnHintEnabled()) "\nRappel : active ton VPN si le live YouTube est géo-bloqué." else ""
        sendMatchNotification(
            context = context,
            notificationId = 99991,
            title = "Alerte active · $primaryClub & WTT",
            body = "Tu recevras tes rappels personnalisés avant chaque match suivi.$vpnHint",
            subText = "PingPong Tracker",
            accentColor = 0xFFE11D48.toInt(),
            url = "",
            vpnRequired = false,
        )
        return true
    }

    private fun buildNotificationBody(match: MatchItem, showVpnHint: Boolean): String {
        val lines = ArrayList<String>(3)
        val meta = listOf(match.followed.joinToString(" · "), match.broadcast.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        if (meta.isNotEmpty()) lines.add(meta)
        if (match.vpnRequired && showVpnHint) {
            val note = match.vpnNote.trim().ifEmpty { "VPN conseillé (YouTube FR)" }
            lines.add("Active ton VPN : $note")
        }
        return lines.joinToString("\n").ifEmpty { match.competition }
    }

    private fun sendMatchNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        subText: String,
        accentColor: Int,
        url: String,
        vpnRequired: Boolean,
    ) {
        val targetIntent = if (url.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_pingpong)
            .setColor(accentColor)
            .setSubText(subText)
            .setContentTitle(title)
            .setContentText(body.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        if (url.isNotBlank()) {
            val actionLabel = if (vpnRequired) "Ouvrir le live (VPN)" else "Regarder le live"
            builder.addAction(
                R.drawable.ic_notif_pingpong,
                actionLabel,
                contentIntent,
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between check and notify
        }
    }
}
