package ru.ashcrm.app.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message encryption of Web Push (RFC 8291 over RFC 8188 `aes128gcm`): the CRM encrypts a push with the keys of the
 * device, FCM carries only the cipher text (docs/ANDROID_CONTRACT.md §5).
 */
object WebPushCrypto {

    /** The keys of the device: the private P-256 key, its public point (65 bytes, uncompressed) and the auth secret. */
    class Keys(val private: PrivateKey, val public: ByteArray, val auth: ByteArray)

    /** The parameters of P-256, taken once from a generated key (the platform has no named-curve lookup before API 33). */
    private val p256: ECParameterSpec by lazy {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        (g.generateKeyPair().public as ECPublicKey).params
    }

    fun generate(): Keys {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val pair = g.generateKeyPair()
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Keys(pair.private, rawPublic(pair.public as ECPublicKey), auth)
    }

    /** Keys from the raw private scalar (32 bytes): test vectors give it so. */
    fun fromScalar(d: ByteArray, public: ByteArray, auth: ByteArray): Keys =
        Keys(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), p256)), public, auth)

    fun restore(pkcs8: ByteArray, public: ByteArray, auth: ByteArray): Keys =
        Keys(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)), public, auth)

    /** Decrypts one `aes128gcm` body; throws [IllegalArgumentException] when it is not ours or damaged. */
    fun decrypt(body: ByteArray, keys: Keys): ByteArray {
        require(body.size > 21) { "short body" }
        val head = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { head.get(it) }
        val rs = head.int.toLong() and 0xffffffffL
        val idLen = head.get().toInt() and 0xff
        require(idLen == 65) { "key id is not a P-256 point" }
        val serverPublic = ByteArray(idLen).also { head.get(it) }
        val cipherText = ByteArray(head.remaining()).also { head.get(it) }
        require(rs >= 18 && cipherText.size <= rs) { "only one record is expected" }

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keys.private)
        agreement.doPhase(publicKey(serverPublic, p256), true)
        val ecdh = agreement.generateSecret()

        // RFC 8291 §3.4: the auth secret and both public keys go into the input key material.
        val prkKey = hmac(keys.auth, ecdh)
        val keyInfo = "WebPush: info".toByteArray() + 0 + keys.public + serverPublic
        val ikm = hmac(prkKey, keyInfo + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val padded = cipher.doFinal(cipherText)
        // RFC 8188 §2: the last record ends with the delimiter 0x02 and zero padding.
        var end = padded.size - 1
        while (end >= 0 && padded[end] == 0.toByte()) end--
        require(end >= 0 && padded[end] == 2.toByte()) { "bad padding" }
        return padded.copyOf(end)
    }

    fun rawPublic(key: ECPublicKey): ByteArray {
        fun fixed(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun publicKey(raw: ByteArray, params: ECParameterSpec): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "not an uncompressed point" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
}
