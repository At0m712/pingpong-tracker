package com.pingpong.tracker.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingpong.tracker.data.MatchFeedParser
import com.pingpong.tracker.data.MatchFetcher
import com.pingpong.tracker.data.MatchStore
import com.pingpong.tracker.notifications.NotificationHelper
import com.pingpong.tracker.widget.PingPongWidgetProvider
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val store = MatchStore(applicationContext)
        val isManual = inputData.getBoolean(KEY_IS_MANUAL, false)
        val url = store.getFeedUrl()

        return try {
            val rawJson = MatchFetcher.fetchJson(url)
            val feed = MatchFeedParser.parse(rawJson)
            store.saveFeedJson(rawJson)
            NotificationHelper.evaluateAndNotify(applicationContext, feed.matches, store)
            PingPongWidgetProvider.updateAllWidgets(applicationContext)
            Result.success()
        } catch (e: Exception) {
            val errorMsg = e.message?.take(120) ?: e.javaClass.simpleName
            store.setLastError("Erreur synchro : $errorMsg")
            PingPongWidgetProvider.updateAllWidgets(applicationContext)
            if (isManual) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "pingpong_periodic_sync"
        private const val IMMEDIATE_WORK_NAME = "pingpong_immediate_sync"
        private const val KEY_IS_MANUAL = "is_manual"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        fun enqueueImmediateSync(context: Context, isManual: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val data = Data.Builder()
                .putBoolean(KEY_IS_MANUAL, isManual)
                .build()

            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }
}
