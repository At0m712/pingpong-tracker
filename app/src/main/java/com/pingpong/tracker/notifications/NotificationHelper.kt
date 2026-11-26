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

        for (match in matches) {
            if (match.isFinished) continue
            // Never notify for club matches outside the user's followed clubs
            if (match.isClub && trackedClubs.none { club -> match.matchesClub(club) }) continue

            val startInstant = match.startInstant()
            val minutesUntil = startInstant?.let { Duration.between(now, it).toMinutes() }

            // Trigger 1: H-30 min (window 20..40 min before start_iso)
            if (match.isScheduled && minutesUntil != null && minutesUntil in 20L..40L) {
                val key30m = MatchStore.key30m(match.id)
                if (!store.hasNotified(key30m)) {
                    val title = "🏓 Dans ~$minutesUntil min · ${match.competition}"
                    val body = buildNotificationBody(match)
                    sendMatchNotification(
                        context = context,
                        notificationId = key30m.hashCode(),
                        title = title,
                        body = body,
                        url = match.url,
                    )
                    store.markNotified(key30m, now.toEpochMilli())
                }
            }

            // Trigger 2: Coup d'envoi / Imminent (0..15 min before start_iso OR status == "live")
            val isImminent = match.isScheduled && minutesUntil != null && minutesUntil in 0L..15L
            if (match.isLive || isImminent) {
                val keyLive = MatchStore.keyLive(match.id)
                if (!store.hasNotified(keyLive)) {
                    val title = if (match.isLive) {
                        "🔴 EN DIRECT · ${match.competition}"
                    } else {
                        "⚡ Coup d'envoi imminent · ${match.competition}"
                    }
                    val body = buildNotificationBody(match)
                    sendMatchNotification(
                        context = context,
                        notificationId = keyLive.hashCode(),
                        title = title,
                        body = body,
                        url = match.url,
                    )
                    store.markNotified(keyLive, now.toEpochMilli())
                }
            }
        }
    }

    private fun buildNotificationBody(match: MatchItem): String {
        val line1 = MatchFormatter.formatMatchTitle(match)
        val meta = MatchFormatter.formatMetaLine(match)
        return if (meta.isNotEmpty()) "$line1\n$meta" else line1
    }

    private fun sendMatchNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        url: String,
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
            .setContentTitle(title)
            .setContentText(body.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        if (url.isNotBlank()) {
            builder.addAction(
                R.drawable.ic_notif_pingpong,
                "Regarder le live",
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
