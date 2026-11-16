package ru.ashcrm.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ashcrm.app.net.LocationAnswer
import ru.ashcrm.app.net.json

class LocateTest {
    @Test fun `silent push goes to the quiet delivery channel`() {
        assertEquals(Notifier.CH_DELIVERY_SILENT, Notifier.channelOf("delivery", silent = true))
        assertEquals(Notifier.CH_DELIVERY_SILENT, Notifier.channelOf("dm", silent = true))
        assertEquals(Notifier.CH_DELIVERY_SILENT, Notifier.channelOf("delivery", alert = true, silent = true))
        assertEquals(Notifier.CH_DELIVERY, Notifier.channelOf("delivery", silent = false))
    }

    @Test fun `locate and silent are read from the push body`() {
        val p = json.decodeFromString<Payload>(
            """{"type":"delivery","category":"delivery","silent":true,"locate":"req-1",
               "title":"Диспетчер запросил местоположение","body":"Отправится автоматически",
               "url":"/delivery?locate=req-1","tag":"delivery-locate"}"""
        )
        assertTrue(p.silent)
        assertEquals("req-1", p.locate)
        assertEquals("delivery-locate", p.tag)
        assertFalse(json.decodeFromString<Payload>("""{"type":"delivery"}""").silent)
        assertEquals("", json.decodeFromString<Payload>("""{"type":"delivery"}""").locate)
    }

    @Test fun `location answer with a place omits geo_error`() {
        val body = json.encodeToString(
            LocationAnswer(requestId = "r1", lat = 55.75, lon = 37.61, accuracy = 18.5, locatedAt = "2026-11-16T10:00:00Z")
        )
        assertTrue(body.contains("\"lat\":55.75"))
        assertTrue(body.contains("\"request_id\":\"r1\""))
        assertFalse(body.contains("geo_error"))
    }

    @Test fun `location answer without a place sends geo_error`() {
        val body = json.encodeToString(LocationAnswer(requestId = "r2", geoError = "denied"))
        assertEquals("""{"request_id":"r2","geo_error":"denied"}""", body)
        val parsed = json.decodeFromString<LocationAnswer>(body)
        assertEquals("denied", parsed.geoError)
        assertNull(parsed.lat)
    }
}
