package ru.ashcrm.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph

/** «Прочитано» in a notification: the card goes away at once, the CRM marks it read on every device (§3.1). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_READ) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        Notifier.clear(ctx, listOf(key))
        val g = ctx.graph
        val done = goAsync()
        g.scope.launch {
            try {
                val portal = g.prefs.get(Prefs.PORTAL) ?: return@launch
                val access = g.auth.accessToken(portal) ?: return@launch
                g.api.notifyRead(portal, access, key)
            } catch (_: Exception) {
                // No network: the card is gone here; the CRM still shows it unread, which is the safe side.
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "ru.ashcrm.app.READ"
        const val EXTRA_KEY = "key"
    }
}
