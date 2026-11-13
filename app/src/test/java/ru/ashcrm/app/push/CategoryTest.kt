package ru.ashcrm.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ashcrm.app.net.json

class CategoryTest {
    @Test fun `every category of the contract is known, an unknown one or none is not`() {
        val keys = listOf(
            "call_missed", "call_incoming", "queue", "client_message", "colleague_direct", "group_message", "mention",
            "conversation_assigned", "task", "calendar", "policy", "delivery", "alert",
        )
        assertEquals(keys, Category.entries.map { it.key })
        assertNull(Category.of("something_new"))
        assertNull(Category.of(""))
        assertNull(Category.of(null))
    }

    @Test fun `colors are those of the contract`() {
        assertEquals(0xFFD93B30.toInt(), Category.of("call_missed")!!.color)
        assertEquals(0xFF2EAD5B.toInt(), Category.of("call_incoming")!!.color)
        assertEquals(0xFF3390EC.toInt(), Category.of("queue")!!.color)
        assertEquals(0xFF8E5CD9.toInt(), Category.of("mention")!!.color)
        assertEquals(0xFFD93B30.toInt(), Category.of("alert")!!.color)
    }

    @Test fun `the summary counts calls, clients and colleagues, the rest as more`() {
        val c = listOf("call_missed", "call_missed", "queue", "client_message", "colleague_direct").map(Category::of)
        assertEquals("5 уведомлений: 2 звонка, 2 клиента, 1 коллега", Category.summary(c))
        assertEquals(
            "3 уведомления: 1 звонок, ещё 2",
            Category.summary(listOf(Category.CallMissed, Category.Task, null)),
        )
        assertEquals("2 уведомления", Category.summary(listOf(Category.Task, null)))
        assertEquals("21 уведомление: 21 коллега", Category.summary(List(21) { Category.GroupMessage }))
    }

    @Test fun `russian plurals`() {
        fun p(n: Int) = Category.plural(n, "звонок", "звонка", "звонков")
        assertEquals(listOf("звонок", "звонка", "звонков", "звонков", "звонка", "звонок", "звонков"), listOf(1, 3, 5, 11, 22, 101, 112).map(::p))
    }

    @Test fun `category comes with the push, older CRM sends none`() {
        assertEquals("call_missed", json.decodeFromString<Payload>("""{"type":"call","title":"+7 900 123-45-67","category":"call_missed"}""").category)
        assertEquals("", json.decodeFromString<Payload>("""{"type":"call","title":"Пропущенный звонок"}""").category)
    }
}
