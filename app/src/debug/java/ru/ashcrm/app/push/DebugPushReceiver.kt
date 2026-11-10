package ru.ashcrm.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ashcrm.app.graph

/**
 * Debug only. `KEYS` prints the public keys of push encryption (what the CRM would get in a subscription);
 * `PUSH --es p <base64url>` handles an encrypted body exactly like a message of FCM (on a worker thread, as FCM does).
 */
class DebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        ctx.graph.scope.launch {
            try {
                when (intent.action) {
                    "ru.ashcrm.app.debug.KEYS" -> {
                        val k = ctx.graph.pushKeys.get()
                        Log.i("AshPush", "KEYS p256dh=${PushKeys.b64(k.public)} auth=${PushKeys.b64(k.auth)}")
                        Log.i("AshPush", "FCM token=${Register.fcmToken(ctx.graph.prefs) ?: "none"}")
                    }
                    "ru.ashcrm.app.debug.REFRESH" -> {
                        val g = ctx.graph
                        val portal = g.prefs.get(ru.ashcrm.app.data.Prefs.PORTAL) ?: return@launch
                        val key = ru.ashcrm.app.net.DeviceKey().publicKey()
                        g.auth.expireAccess()
                        val ok = runCatching { g.auth.accessToken(portal) != null }
                        Log.i("AshPush", "REFRESH device_key=${key?.take(16)}… ok=${ok.getOrNull()} err=${ok.exceptionOrNull()?.message}")
                    }
                    "ru.ashcrm.app.debug.PUSH" -> {
                        val p = intent.getStringExtra("p") ?: return@launch
                        val ok = withContext(Dispatchers.IO) { PushHandler.handle(ctx, p) }
                        Log.i("AshPush", "PUSH handled=$ok")
                    }
                }
            } finally {
                done.finish()
            }
        }
    }
}
