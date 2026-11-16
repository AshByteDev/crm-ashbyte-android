package ru.ashcrm.app.push

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.runBlocking
import ru.ashcrm.app.graph
import ru.ashcrm.app.net.json
import java.util.Base64

/** One encrypted push (`p` of docs/ANDROID_CONTRACT.md §5) → decrypt → notification. */
object PushHandler {
    private const val TAG = "AshPush"

    /** False — not ours or damaged (the keys changed since the CRM learnt them): the caller registers again. */
    fun handle(ctx: Context, p: String): Boolean = try {
        val keys = runBlocking { ctx.graph.pushKeys.get() }
        val plain = WebPushCrypto.decrypt(Base64.getUrlDecoder().decode(p.trimEnd('=')), keys)
        val payload = json.decodeFromString<Payload>(String(plain))
        if (payload.locate.isNotEmpty()) {
            Locate.handle(ctx, payload)
            return true
        }
        val visible = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }.getOrDefault(false)
        // `silent` of the push (§5.1) or the app is already on screen — no sound either way.
        Notifier.handle(ctx, payload, silent = visible || payload.silent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "push dropped: ${e.javaClass.simpleName}")
        false
    }
}
