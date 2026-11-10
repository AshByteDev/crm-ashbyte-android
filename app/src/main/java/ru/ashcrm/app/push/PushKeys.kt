package ru.ashcrm.app.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.data.SecureBox
import java.util.Base64

/** The encryption keys of this device: made once, the private key is kept sealed and never leaves the phone. */
class PushKeys(private val prefs: Prefs, private val box: SecureBox) {
    private val mutex = Mutex()
    @Volatile private var cached: WebPushCrypto.Keys? = null

    suspend fun get(): WebPushCrypto.Keys = cached ?: mutex.withLock {
        cached ?: (load() ?: create()).also { cached = it }
    }

    private suspend fun load(): WebPushCrypto.Keys? {
        val private = box.open(prefs.get(Prefs.PUSH_PRIVATE)) ?: return null
        val public = box.open(prefs.get(Prefs.PUSH_PUBLIC)) ?: return null
        val auth = box.open(prefs.get(Prefs.PUSH_AUTH)) ?: return null
        return runCatching { WebPushCrypto.restore(private, public, auth) }.getOrNull()
    }

    private suspend fun create(): WebPushCrypto.Keys {
        val k = WebPushCrypto.generate()
        prefs.set(Prefs.PUSH_PRIVATE, box.seal(k.private.encoded))
        prefs.set(Prefs.PUSH_PUBLIC, box.seal(k.public))
        prefs.set(Prefs.PUSH_AUTH, box.seal(k.auth))
        // New keys mean the CRM must learn them: the old registration is no longer readable.
        prefs.set(Prefs.ENDPOINT, null)
        return k
    }

    companion object {
        fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
