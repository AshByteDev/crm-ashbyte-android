package ru.ashcrm.app.net

import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.data.SecureBox
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * The device session of the app (docs/ANDROID_CONTRACT.md §3): the connect page in the WebView gives a one-time code,
 * the app exchanges it for keys. The refresh key is single-use, so it is saved before anything else uses it.
 */
class DeviceAuth(private val prefs: Prefs, private val box: SecureBox, private val api: CrmApi, private val key: DeviceKey = DeviceKey()) {

    /** One connection attempt: what the app put into the address and must see again in the answer. */
    class Pending(val verifier: String, val challenge: String, val state: String)

    private val mutex = Mutex()
    private var access: String? = null
    private var accessUntil: Instant = Instant.EPOCH
    private var pending: Pending? = null

    private val _connected = MutableStateFlow<Boolean?>(null)
    /** Null until read; false — no device session (never connected, ended in the CRM or logged out). */
    val connected: StateFlow<Boolean?> = _connected

    suspend fun load() {
        _connected.value = box.open(prefs.get(Prefs.REFRESH)) != null
    }

    /** The address of the connect page for [portal]. */
    fun connectUrl(portal: String): String {
        val p = Pending(verifier = random(32), challenge = "", state = random(24)).let {
            Pending(it.verifier, b64(MessageDigest.getInstance("SHA-256").digest(it.verifier.toByteArray())), it.state)
        }
        pending = p
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$portal/desktop-connect?app=android&challenge=${p.challenge}&state=${p.state}&device=${q(deviceName())}"
    }

    /** True when [state] is the one of the attempt under way (compared in constant time). */
    fun expects(state: String?): Boolean {
        val p = pending ?: return false
        return state != null && MessageDigest.isEqual(p.state.toByteArray(), state.toByteArray())
    }

    fun cancel() {
        pending = null
    }

    /** Exchanges the code of the connect page; throws on a wrong state, a used code or no network. */
    suspend fun finish(portal: String, code: String, state: String) = mutex.withLock {
        val p = pending?.takeIf { expects(state) } ?: throw IllegalStateException("state mismatch")
        pending = null
        key.reset() // a new session gets a new key of the phone
        save(api.token(portal, code, p.verifier, key.publicKey()))
        runCatching { api.me(portal, access!!) }.getOrNull()?.let {
            prefs.set(Prefs.USER_NAME, it.displayName)
            prefs.set(Prefs.TENANT_NAME, it.tenantName)
        }
    }

    /** A valid access key, refreshed when needed; null — the device is not connected (any more). */
    suspend fun accessToken(portal: String): String? = mutex.withLock {
        if (access != null && Instant.now().isBefore(accessUntil.minusSeconds(60))) return access
        val refresh = box.open(prefs.get(Prefs.REFRESH))?.let { String(it) } ?: return null.also { _connected.value = false }
        repeat(3) { attempt ->
            try {
                val ts = Instant.now().epochSecond
                save(api.refresh(portal, refresh, key.publicKey(), ts, key.sign(refresh, ts)))
                return access
            } catch (e: HttpError) {
                when (e.status) {
                    401 -> { forget(); return null }
                    409 -> delay(1000L * (attempt + 1)) // two refreshes at once: the other one wins, try again
                    else -> throw e
                }
            }
        }
        null
    }

    /** Forgets the access key so the next [accessToken] refreshes (the debug build checks the signed refresh so). */
    internal fun expireAccess() {
        accessUntil = Instant.EPOCH
    }

    /** «Отключить уведомления»: ends the session in the CRM when reachable and forgets the keys anyway. */
    suspend fun disconnect(portal: String?, beforeLogout: suspend (String) -> Unit = {}) {
        if (portal != null) runCatching {
            accessToken(portal)?.let { beforeLogout(it); api.logout(portal, it) }
        }
        mutex.withLock { forget() }
    }

    private suspend fun save(t: DeviceTokens) {
        prefs.set(Prefs.REFRESH, box.seal(t.refresh.toByteArray()))
        access = t.access
        accessUntil = runCatching { Instant.parse(t.expiresAt) }.getOrDefault(Instant.now().plusSeconds(600))
        _connected.value = true
    }

    private suspend fun forget() {
        key.reset()
        access = null
        accessUntil = Instant.EPOCH
        prefs.set(Prefs.REFRESH, null)
        prefs.set(Prefs.ENDPOINT, null)
        prefs.set(Prefs.USER_NAME, null)
        prefs.set(Prefs.TENANT_NAME, null)
        _connected.value = false
    }

    companion object {
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(60)
        }

        private fun random(n: Int) = b64(ByteArray(n).also { SecureRandom().nextBytes(it) })
        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
