package ru.ashcrm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLoginTest {
    private val token = "A".repeat(40) + "b-_"

    @Test fun `login on the phone and on the computer`() {
        assertEquals(QrLogin("https://crm.linkpart.ru", "https://crm.linkpart.ru/qr#out.$token"), QrLogin.parse("https://crm.linkpart.ru/qr#out.$token"))
        assertEquals("https://crm.linkpart.ru/qr#in.$token", QrLogin.parse(" https://CRM.linkpart.ru/qr#in.$token\n")?.url)
        assertEquals("crm.linkpart.ru", QrLogin.parse("https://crm.linkpart.ru/qr#in.$token")?.host)
    }

    @Test fun `the port stays, http only in the local network`() {
        assertEquals("http://192.168.1.5:8780", QrLogin.parse("http://192.168.1.5:8780/qr#in.$token")?.portal)
        assertNull(QrLogin.parse("http://crm.linkpart.ru/qr#in.$token"))
    }

    @Test fun `anything else is not a login code`() {
        listOf(
            "https://example.com",
            "https://crm.linkpart.ru/qr",
            "https://crm.linkpart.ru/qr#in.short",
            "https://crm.linkpart.ru/qr#up.$token",
            "https://crm.linkpart.ru/qr#in.${token}x",
            "https://crm.linkpart.ru/qr?x=1#in.$token",
            "https://crm.linkpart.ru/other#in.$token",
            "https://user@crm.linkpart.ru/qr#in.$token",
            "ftp://crm.linkpart.ru/qr#in.$token",
            "javascript:alert(1)",
            "просто текст",
        ).forEach { assertNull(it, QrLogin.parse(it)) }
    }
}
