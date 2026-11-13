package ru.ashcrm.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Why the page of the CRM does not open: «Проверить подключение» on the screen «Нет связи с CRM». */
enum class Reach {
    /** The CRM answers: the page may be loaded again. */
    Ok,
    /** The internet works, only the CRM does not answer. */
    CrmDown,
    NoInternet;

    companion object {
        /** Any one of them answering means the internet works; HTTPS, so a Wi-Fi with a login page does not pass for one. */
        private val PROBES = listOf("https://ya.ru", "https://www.google.com/generate_204", "https://github.com")

        suspend fun check(ctx: Context, base: OkHttpClient, portal: String): Reach {
            if (!hasNetwork(ctx)) return NoInternet
            val http = base.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS)
                .build()
            return coroutineScope {
                val crm = async { http.answers(portal) { it < 500 } }
                val probes = PROBES.map { async { http.answers(it) { true } } }
                when {
                    crm.await() -> Ok.also { probes.forEach { it.cancel() } }
                    probes.any { it.await() } -> CrmDown.also { probes.forEach { it.cancel() } }
                    else -> NoInternet
                }
            }
        }

        /** Android itself knows there is no network at all (airplane mode, Wi-Fi and mobile data off). */
        private fun hasNetwork(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private suspend fun OkHttpClient.answers(url: String, ok: (Int) -> Boolean): Boolean = suspendCancellableCoroutine { cont ->
            val call = newCall(Request.Builder().url(url).head().build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(false)
                override fun onResponse(call: Call, response: Response) = response.use { cont.resume(ok(it.code)) }
            })
        }
    }
}
