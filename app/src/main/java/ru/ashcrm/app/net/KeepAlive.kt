package ru.ashcrm.app.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the session of the phone alive (30 days from the last refresh): once a day, when there is a network, the app
 * refreshes its key — even if nobody opened it for months. No network (a holiday) — the work waits for it; one short
 * request a day, Android picks the moment.
 */
object KeepAlive {
    private const val WORK = "keep-alive"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS, 6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val g = applicationContext.graph
            val portal = g.prefs.get(Prefs.PORTAL) ?: return Result.success()
            return try {
                g.auth.accessToken(portal) // refreshes when the short access key has expired — after a day it has
                Result.success()
            } catch (e: HttpError) {
                if (e.status >= 500 || e.status == 429) Result.retry() else Result.success()
            } catch (_: IOException) {
                Result.retry()
            }
        }
    }
}
