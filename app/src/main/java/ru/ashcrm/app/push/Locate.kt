package ru.ashcrm.app.push

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ru.ashcrm.app.R
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph
import ru.ashcrm.app.net.HttpError
import ru.ashcrm.app.net.LocationAnswer
import ru.ashcrm.app.ui.Perms
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * «Уточнить координаты» (§7.5): a push with `locate` shows a quiet card, the phone finds itself once and answers
 * `POST /delivery/location`. No network — retry for 10 minutes. No place permission — `geo_error: denied`.
 */
object Locate {
    private const val TAG = "AshLocate"
    const val EXTRA_REQUEST = "request_id"
    const val EXTRA_URL = "url"
    const val EXTRA_TAG = "tag"
    const val EXTRA_DEADLINE = "deadline"
    const val EXTRA_ANSWER = "answer_json"
    private const val WORK = "locate"
    private const val RETRY_MS = 10 * 60_000L
    private const val FIX_MS = 20_000L
    private const val LAST_MS = 2 * 60_000L

    /** A locate push arrived: remember the driver, show the quiet card, start the short location service. */
    fun handle(ctx: Context, p: Payload) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ctx.graph.prefs.set(Prefs.IS_DRIVER, true)
        }
        val tag = p.tag.ifEmpty { "delivery-locate" }
        val card = p.copy(silent = true, tag = tag, type = p.type.ifEmpty { "delivery" }, category = p.category.ifEmpty { "delivery" })
        Notifier.show(ctx, card, silent = true)
        val i = Intent(ctx, LocateService::class.java)
            .putExtra(EXTRA_REQUEST, p.locate)
            .putExtra(EXTRA_URL, p.url)
            .putExtra(EXTRA_TAG, tag)
            .putExtra(EXTRA_DEADLINE, System.currentTimeMillis() + RETRY_MS)
        ContextCompat.startForegroundService(ctx, i)
    }

    /** Measure once and POST; on no network keep the answer and wait for connectivity. */
    suspend fun run(ctx: Context, requestId: String, url: String, tag: String, deadline: Long, ready: LocationAnswer? = null): Boolean {
        val answer = ready ?: measure(ctx, requestId)
        return when (val r = post(ctx, answer)) {
            Post.Ok -> {
                done(ctx, url, tag, ok = answer.geoError == null)
                true
            }
            Post.Auth -> {
                done(ctx, url, tag, ok = false)
                true
            }
            Post.Network -> {
                if (System.currentTimeMillis() >= deadline) {
                    done(ctx, url, tag, ok = false)
                    true
                } else {
                    enqueueRetry(ctx, answer, url, tag, deadline)
                    false
                }
            }
        }
    }

    suspend fun measure(ctx: Context, requestId: String): LocationAnswer {
        if (Perms.location(ctx) == Perms.Location.None) return LocationAnswer(requestId, geoError = "denied")
        // Android 10+: without «Всегда» a fix from the background is refused.
        if (Build.VERSION.SDK_INT >= 29 && !Perms.backgroundLocation(ctx) && !appVisible()) {
            return LocationAnswer(requestId, geoError = "denied")
        }
        return try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val cancel = CancellationTokenSource()
            val current = withTimeoutOrNull(FIX_MS) {
                runCatching {
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token).await()
                }.onFailure { Log.w(TAG, "current: ${it.javaClass.simpleName}") }.getOrNull()
            }
            cancel.cancel()
            val loc = current ?: runCatching { client.lastLocation.await() }.getOrNull()?.takeIf { last ->
                val ageMs = when {
                    last.elapsedRealtimeNanos > 0 ->
                        (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
                    last.time > 0 -> System.currentTimeMillis() - last.time
                    else -> Long.MAX_VALUE
                }
                ageMs <= LAST_MS
            }
            if (loc != null) LocationAnswer(
                requestId = requestId,
                lat = loc.latitude,
                lon = loc.longitude,
                accuracy = loc.accuracy.toDouble(),
                locatedAt = Instant.ofEpochMilli(loc.time).toString(),
            ) else LocationAnswer(requestId, geoError = "timeout")
        } catch (_: SecurityException) {
            LocationAnswer(requestId, geoError = "denied")
        } catch (e: Exception) {
            Log.w(TAG, "measure: ${e.javaClass.simpleName}")
            LocationAnswer(requestId, geoError = "unavailable")
        }
    }

    private suspend fun post(ctx: Context, answer: LocationAnswer): Post {
        val g = ctx.graph
        val portal = g.prefs.get(Prefs.PORTAL) ?: return Post.Auth
        return try {
            val access = g.auth.accessToken(portal) ?: return Post.Auth
            try {
                g.api.deliveryLocation(portal, access, answer)
                Post.Ok
            } catch (e: HttpError) {
                when (e.status) {
                    401 -> {
                        val again = g.auth.accessToken(portal) ?: return Post.Auth
                        g.api.deliveryLocation(portal, again, answer)
                        Post.Ok
                    }
                    in 500..599, 429 -> Post.Network
                    else -> Post.Ok // 4xx other than 401: the request is gone; do not loop
                }
            }
        } catch (_: IOException) {
            Post.Network
        } catch (e: Exception) {
            Log.w(TAG, "post: ${e.javaClass.simpleName}")
            Post.Ok
        }
    }

    private fun enqueueRetry(ctx: Context, answer: LocationAnswer, url: String, tag: String, deadline: Long) {
        val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
        val req = OneTimeWorkRequestBuilder<LocateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    EXTRA_REQUEST to answer.requestId,
                    EXTRA_URL to url,
                    EXTRA_TAG to tag,
                    EXTRA_DEADLINE to deadline,
                    EXTRA_ANSWER to ru.ashcrm.app.net.json.encodeToString(LocationAnswer.serializer(), answer),
                )
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("$WORK-${answer.requestId}", ExistingWorkPolicy.REPLACE, req)
        // [left] kept so a late wake after the 10-minute window still finishes with a timeout answer.
        Log.d(TAG, "retry until ${deadline}, ${left}ms left")
    }

    private fun done(ctx: Context, url: String, tag: String, ok: Boolean) {
        Notifier.show(
            ctx,
            Payload(
                type = "delivery",
                category = "delivery",
                silent = true,
                title = if (ok) "Местоположение отправлено" else "Не удалось определить местоположение",
                body = if (ok) "Диспетчер видит, где вы" else "Проверьте разрешение на местоположение «Всегда»",
                url = url,
                tag = tag,
            ),
            silent = true,
        )
    }

    private fun appVisible(): Boolean = runCatching {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private enum class Post { Ok, Auth, Network }

    /** The quiet card the short location service holds while it measures (§7.5). */
    fun measuringNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, Notifier.CH_DELIVERY_SILENT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Определение местоположения")
            .setContentText("Ответ диспетчеру «Доставки»")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

/** Short foreground service: one fix, one POST, then stop. Needed on Android 14+ for a background location. */
class LocateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(Locate.EXTRA_REQUEST) ?: return stop()
        val url = intent.getStringExtra(Locate.EXTRA_URL).orEmpty()
        val tag = intent.getStringExtra(Locate.EXTRA_TAG) ?: "delivery-locate"
        val deadline = intent.getLongExtra(Locate.EXTRA_DEADLINE, System.currentTimeMillis() + 10 * 60_000L)
        val n = Locate.measuringNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(42, n)
        }
        scope.launch {
            try {
                Locate.run(this@LocateService, requestId, url, tag, deadline)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}

/** Retries the POST when the network comes back, within the 10-minute window (§7.5). */
class LocateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(Locate.EXTRA_REQUEST) ?: return Result.success()
        val url = inputData.getString(Locate.EXTRA_URL).orEmpty()
        val tag = inputData.getString(Locate.EXTRA_TAG) ?: "delivery-locate"
        val deadline = inputData.getLong(Locate.EXTRA_DEADLINE, 0)
        if (deadline > 0 && System.currentTimeMillis() >= deadline) {
            Locate.run(applicationContext, requestId, url, tag, deadline, LocationAnswer(requestId, geoError = "timeout"))
            return Result.success()
        }
        val ready = inputData.getString(Locate.EXTRA_ANSWER)?.let {
            runCatching { ru.ashcrm.app.net.json.decodeFromString(LocationAnswer.serializer(), it) }.getOrNull()
        }
        return if (Locate.run(applicationContext, requestId, url, tag, deadline, ready)) Result.success() else Result.retry()
    }
}
