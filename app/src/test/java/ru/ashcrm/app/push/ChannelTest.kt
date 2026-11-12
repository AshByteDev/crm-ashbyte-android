package ru.ashcrm.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ashcrm.app.net.json

class ChannelTest {
    @Test fun `delivery goes to its own channels`() {
        assertEquals(Notifier.CH_DELIVERY_ALERT, Notifier.channelOf("delivery", alert = true))
        assertEquals(Notifier.CH_DELIVERY, Notifier.channelOf("delivery", alert = false))
        assertEquals(Notifier.CH_DELIVERY, Notifier.channelOf("delivery"))
    }

    @Test fun `alert counts only for delivery, an unknown type is a message of the team`() {
        assertEquals(Notifier.channelOf("dm"), Notifier.channelOf("something-new"))
        assertEquals(Notifier.CH_TEAM, Notifier.channelOf("dm", alert = true))
        assertEquals(Notifier.CH_CLIENTS, Notifier.channelOf("queue", alert = true))
    }

    @Test fun `alert is false when the push has none`() {
        assertFalse(json.decodeFromString<Payload>("""{"type":"delivery","title":"Заявка 12","url":"/delivery/12","tag":"delivery-12"}""").alert)
        assertTrue(json.decodeFromString<Payload>("""{"type":"delivery","alert":true}""").alert)
    }
}
