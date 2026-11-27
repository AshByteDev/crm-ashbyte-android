package ru.ashcrm.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ashcrm.app.net.json
import kotlinx.serialization.json.JsonObject

class BusyTest {
    @Test fun `on keeps busy, off ends it at once`() {
        val b = Busy()
        assertFalse(b.active(0))
        b.message(true, 1_000)
        assertTrue(b.active(1_000))
        assertTrue(b.active(60_000))
        b.message(false, 61_000)
        assertFalse(b.active(61_000))
        assertNull(b.expiresAt())
    }

    @Test fun `without a repeat for 90 s busy ends by itself`() {
        val b = Busy()
        b.message(true, 0)
        assertEquals(90_000L, b.expiresAt())
        assertTrue(b.active(89_999))
        assertFalse(b.active(90_000))
    }

    @Test fun `a repeat every 30 s keeps it going`() {
        val b = Busy()
        for (t in 0L..300_000L step 30_000L) b.message(true, t)
        assertTrue(b.active(300_000 + 89_000))
        assertEquals(390_000L, b.expiresAt())
    }

    @Test fun `a new WebView is not busy`() {
        val b = Busy()
        b.message(true, 0)
        b.reset()
        assertFalse(b.active(1))
    }

    @Test fun `the message of the page parses`() {
        assertEquals(true, busyOn(json.decodeFromString<JsonObject>("""{"t":"busy","on":true}""")))
        assertEquals(false, busyOn(json.decodeFromString<JsonObject>("""{"t":"busy","on":false}""")))
        assertEquals(false, busyOn(json.decodeFromString<JsonObject>("""{"t":"busy"}""")))
        assertEquals(false, busyOn(json.decodeFromString<JsonObject>("""{"t":"busy","on":"yes"}""")))
    }
}
