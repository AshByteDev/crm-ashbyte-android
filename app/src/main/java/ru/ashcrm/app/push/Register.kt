package ru.ashcrm.app.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.ashcrm.app.BuildConfig
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph
import ru.ashcrm.app.net.HttpError
import ru.ashcrm.app.net.Subscription
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tells the CRM where to deliver: `fcm:<token>` and the encryption keys (docs/ANDROID_CONTRACT.md §4). Runs at every
 * start (one request), when FCM changes the token and after a push that could not be read; waits for the network.
 */
object Register {
    private const val WORK = "register"

    fun enqueue(ctx: Context, replace: Boolean = false) {
        if (!BuildConfig.HAS_FIREBASE) return
        val req = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /**
     * The FCM token: FCM reports it to [PushService.onRegistered]; until it did, ask FCM to register and wait a little.
     * Null — no Google services on the phone (or no Firebase config in this build).
     */
    suspend fun fcmToken(prefs: Prefs): String? {
        if (!BuildConfig.HAS_FIREBASE) return null
        prefs.get(Prefs.FCM_TOKEN)?.let { return it }
        val asked = runCatching {
            suspendCancellableCoroutine { c ->
                FirebaseMessaging.getInstance().register()
                    .addOnSuccessListener { c.resume(Unit) }
                    .addOnFailureListener { c.resumeWithException(it) }
            }
        }
        asked.exceptionOrNull()?.let { android.util.Log.w("AshPush", "FCM register failed: $it"); return null }
        repeat(20) {
            prefs.get(Prefs.FCM_TOKEN)?.let { return it }
            kotlinx.coroutines.delay(500)
        }
        return null
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val g = applicationContext.graph
            val portal = g.prefs.get(Prefs.PORTAL) ?: return Result.success()
            if (g.prefs.get(Prefs.BLOCKED) == true) return Result.success()
            return try {
                val access = g.auth.accessToken(portal) ?: return Result.success()
                val token = fcmToken(g.prefs) ?: return if (hasGoogle(applicationContext)) Result.retry()
                else fail(g.prefs, "Нет сервисов Google: уведомления через FCM недоступны")
                val keys = g.pushKeys.get()
                val endpoint = "fcm:$token"
                val old = g.prefs.get(Prefs.ENDPOINT)
                if (old != null && old != endpoint) runCatching { g.api.unsubscribe(portal, access, old) }
                g.api.subscribe(portal, access, Subscription(endpoint, PushKeys.b64(keys.public), PushKeys.b64(keys.auth)))
                g.prefs.set(Prefs.ENDPOINT, endpoint)
                g.prefs.set(Prefs.REGISTERED_AT, System.currentTimeMillis())
                g.prefs.set(Prefs.REGISTER_ERROR, null)
                Result.success()
            } catch (e: HttpError) {
                when {
                    e.status >= 500 || e.status == 429 -> Result.retry()
                    e.status == 403 || e.status == 404 -> fail(g.prefs, "CRM не принимает уведомления приложения (${e.status}): нужна новая версия CRM")
                    else -> fail(g.prefs, e.message ?: "Ошибка ${e.status}")
                }
            } catch (_: IOException) {
                Result.retry()
            }
        }

        /** Google Play services are installed and enabled: FCM can deliver, a missing token is only a delay. */
        private fun hasGoogle(ctx: Context) = runCatching {
            ctx.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled
        }.getOrDefault(false)

        private suspend fun fail(prefs: Prefs, why: String): Result {
            prefs.set(Prefs.REGISTER_ERROR, why)
            return Result.success()
        }
    }
}
