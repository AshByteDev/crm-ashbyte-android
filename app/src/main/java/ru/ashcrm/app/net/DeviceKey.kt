package ru.ashcrm.app.net

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The key of this phone (docs/ANDROID_CONTRACT.md §3.3): a P-256 pair made inside the Android Keystore — in the
 * secure chip where the phone has one. The private half can never be read out, so a refresh key copied off the phone
 * is useless without the phone itself.
 */
class DeviceKey(private val alias: String = "ashcrm.device") {

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The public key (X.509 SubjectPublicKeyInfo, base64url) — made on first use. Null — the Keystore failed. */
    fun publicKey(): String? = runCatching {
        val ks = keyStore()
        if (!ks.containsAlias(alias)) create()
        b64(ks.getCertificate(alias).publicKey.encoded)
    }.onFailure { Log.w("AshAuth", "device key: ${it.javaClass.simpleName}") }.getOrNull()

    /** Signs [refresh] at [ts] (unix seconds); null — no key (it was lost with a reset of the lock screen). */
    fun sign(refresh: String, ts: Long): String? = runCatching {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return null
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(payload(refresh, ts))
            b64(sign())
        }
    }.getOrNull()

    /** A new connection gets a new key: the old session and its key end together. */
    fun reset() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun create() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(strongBox = true))
            gen.generateKeyPair()
        } catch (_: Exception) {
            // No secure chip (StrongBoxUnavailableException): the Keystore of the main processor — still not exportable.
            gen.initialize(spec(strongBox = false))
            gen.generateKeyPair()
        }
    }

    companion object {
        /** What is signed (§3.3): a fixed prefix, the refresh key and the time, one per line. */
        fun payload(refresh: String, ts: Long): ByteArray = "ashcrm-refresh\n$refresh\n$ts".toByteArray()

        fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
