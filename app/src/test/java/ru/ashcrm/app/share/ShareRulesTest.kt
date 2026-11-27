package ru.ashcrm.app.share

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ashcrm.app.net.json

class ShareRulesTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val ext = mapOf("image/jpeg" to "jpg", "application/pdf" to "pdf")

    @Test fun `the path of a file is parsed, anything else is not`() {
        assertEquals(id to 0, ShareRules.parse(ShareRules.url(id, 0)))
        assertEquals(id to 12, ShareRules.parse("/.ashcrm-share/$id/12"))
        assertNull(ShareRules.parse("/.ashcrm-share/$id/-1"))
        assertNull(ShareRules.parse("/.ashcrm-share/$id/1/2"))
        assertNull(ShareRules.parse("/.ashcrm-share/../$id/1"))
        assertNull(ShareRules.parse("/.ashcrm-share/XYZ/1"))
        assertNull(ShareRules.parse("/api/v1/me"))
        assertNull(ShareRules.parse(null))
    }

    @Test fun `subject and text become one text`() {
        assertEquals("https://ya.ru", ShareRules.text(null, " https://ya.ru "))
        assertEquals("Яндекс\nhttps://ya.ru", ShareRules.text("Яндекс", "https://ya.ru"))
        assertEquals("Смотри: Яндекс https://ya.ru", ShareRules.text("Яндекс", "Смотри: Яндекс https://ya.ru"))
        assertEquals("Тема", ShareRules.text("Тема", null))
        assertEquals("", ShareRules.text(null, null))
    }

    @Test fun `names lose folders and get an extension`() {
        assertEquals("photo.jpg", ShareRules.name("/sdcard/DCIM/photo.jpg", "image/jpeg", 0) { ext[it] })
        assertEquals("IMG_1.jpg", ShareRules.name("IMG_1", "image/jpeg", 0) { ext[it] })
        assertEquals("file-3.pdf", ShareRules.name(null, "application/pdf", 2) { ext[it] })
        assertEquals("file-1", ShareRules.name("", "application/x-unknown", 0) { ext[it] })
        assertEquals("passwd", ShareRules.name("../../etc/passwd", "application/x-unknown", 0) { ext[it] })
        assertEquals("ab.txt", ShareRules.name("a\u0000b.txt", "text/plain", 0) { null })
        assertEquals("env", ShareRules.name(".env", "text/plain", 0) { null })
    }

    @Test fun `only a real type is passed on`() {
        assertEquals("image/jpeg", ShareRules.type("IMAGE/JPEG"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ShareRules.type("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals("application/octet-stream", ShareRules.type(null))
        assertEquals("application/octet-stream", ShareRules.type("*/*"))
        assertEquals("application/octet-stream", ShareRules.type("text/html; charset=utf-8\r\nX: 1"))
    }

    @Test fun `a share waits an hour`() {
        val s = Shared(id, at = 1_000_000)
        assertFalse(ShareRules.expired(s, 1_000_000 + ShareRules.KEEP_MS))
        assertTrue(ShareRules.expired(s, 1_000_001 + ShareRules.KEEP_MS))
        assertTrue(ShareRules.expired(s, 0)) // the clock went back far: not trusted
    }

    @Test fun `the reply lists the files with their addresses`() {
        val s = Shared(id, 0, listOf(SharedFile("a.jpg", "image/jpeg", 10), SharedFile("b.pdf", "application/pdf", 20)), "привет")
        val r = json.decodeFromString<JsonObject>(ShareRules.reply(s))
        assertEquals("share", r["t"]!!.jsonPrimitive.content)
        assertEquals(id, r["id"]!!.jsonPrimitive.content)
        assertEquals("привет", r["text"]!!.jsonPrimitive.content)
        val f = r["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("/.ashcrm-share/$id/0", "/.ashcrm-share/$id/1"), f.map { it["url"]!!.jsonPrimitive.content })
        assertEquals("b.pdf", f[1]["name"]!!.jsonPrimitive.content)
        assertEquals("20", f[1]["size"]!!.jsonPrimitive.content)
    }

    @Test fun `nothing waiting is an empty reply`() {
        val r = json.decodeFromString<JsonObject>(ShareRules.reply(null))
        assertEquals("null", r["id"].toString())
        assertTrue(r["files"]!!.jsonArray.isEmpty())
    }
}
