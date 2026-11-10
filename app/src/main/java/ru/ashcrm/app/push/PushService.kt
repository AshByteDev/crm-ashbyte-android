package ru.ashcrm.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph

/** Receives the encrypted pushes of the CRM through FCM (docs/ANDROID_CONTRACT.md §5) and shows them. */
class PushService : FirebaseMessagingService() {

    /** FCM gave this install a token (the first time, after a reset of Google services, or a new one). */
    override fun onRegistered(token: String) {
        runBlocking { graph.prefs.set(Prefs.FCM_TOKEN, token) }
        Register.enqueue(this, replace = true)
    }

    override fun onUnregistered(token: String) {
        runBlocking { graph.prefs.set(Prefs.FCM_TOKEN, null) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val p = message.data["p"] ?: return
        if (!PushHandler.handle(this, p)) Register.enqueue(this, replace = true)
    }
}
