package ru.ashcrm.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The signed text and the encodings the CRM must check against (docs/ANDROID_CONTRACT.md §3.3). */
class DeviceKeyTest {
    @Test fun payloadIsFixed() =
        assertArrayEquals("ashcrm-refresh\nRT-abc\n1794300000".toByteArray(), DeviceKey.payload("RT-abc", 1794300000))

    @Test fun signatureOfThePayloadVerifiesWithThePublishedKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(DeviceKey.payload("RT", 7)); sign() }
        // What goes over the wire: base64url of the SPKI and of the DER signature.
        val spki = Base64.getUrlDecoder().decode(DeviceKey.b64(pair.public.encoded))
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub); update(DeviceKey.payload("RT", 7)); verify(Base64.getUrlDecoder().decode(DeviceKey.b64(sig)))
        }
        assertTrue(ok)
    }
}
