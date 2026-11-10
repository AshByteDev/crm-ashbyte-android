package ru.ashcrm.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class WebPushCryptoTest {
    private fun b64(s: String) = Base64.getUrlDecoder().decode(s)

    // RFC 8291 Appendix A.
    private val keys = WebPushCrypto.fromScalar(
        d = b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"),
        public = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
        auth = b64("BTBZMqHH6r4Tts7J_aSIgg"),
    )
    private val body = b64(
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
    )

    @Test fun rfc8291Vector() {
        assertEquals("When I grow up, I want to be a watermelon", String(WebPushCrypto.decrypt(body, keys)))
    }

    @Test fun damagedBodyIsRejected() {
        val bad = body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { WebPushCrypto.decrypt(bad, keys) }
    }

    @Test fun otherKeysAreRejected() {
        assertThrows(Exception::class.java) { WebPushCrypto.decrypt(body, WebPushCrypto.generate()) }
    }
}
