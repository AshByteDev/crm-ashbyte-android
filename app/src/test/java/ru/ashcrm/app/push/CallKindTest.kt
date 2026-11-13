package ru.ashcrm.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ashcrm.app.net.json

class CallKindTest {
    @Test fun `every kind of the contract is known, an unknown one is a gray handset, none is not a call`() {
        assertEquals(
            listOf(CallKind.In, CallKind.Out, CallKind.Missed, CallKind.Dropped, CallKind.Spam, CallKind.Ringing),
            listOf("in", "out", "missed", "dropped", "spam", "ringing").map(CallKind::of),
        )
        assertEquals(CallKind.Unknown, CallKind.of("video"))
        assertEquals(0xFF8A8F98.toInt(), CallKind.of("video")!!.color)
        assertNull(CallKind.of(""))
        assertNull(CallKind.of(null))
    }

    @Test fun `colors are those of the contract`() {
        assertEquals(0xFF2EAD5B.toInt(), CallKind.In.color)
        assertEquals(0xFF3390EC.toInt(), CallKind.Out.color)
        assertEquals(0xFFD93B30.toInt(), CallKind.Missed.color)
        assertEquals(0xFF8A8F98.toInt(), CallKind.Dropped.color)
        assertEquals(0xFFF97316.toInt(), CallKind.Spam.color)
        assertEquals(0xFF2EAD5B.toInt(), CallKind.Ringing.color)
    }

    @Test fun `not a call — the body as is`() {
        assertEquals("Привет!", CallKind.text(null, "Привет!"))
    }

    @Test fun `call_kind comes with the push, older CRM sends none`() {
        val p = json.decodeFromString<Payload>("""{"type":"queue","title":"Пётр","body":"2:35 · 08. Ремзона","category":"queue","call_kind":"missed"}""")
        assertEquals("missed", p.callKind)
        assertEquals("", json.decodeFromString<Payload>("""{"type":"queue","title":"Пётр"}""").callKind)
    }
}
