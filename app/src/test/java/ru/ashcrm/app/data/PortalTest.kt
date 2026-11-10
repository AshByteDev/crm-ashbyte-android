package ru.ashcrm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTest {
    @Test fun normalize() {
        assertEquals("https://crm.company.ru", Portal.normalize(" crm.company.ru/ "))
        assertEquals("https://ashcrm.ru:8443", Portal.normalize("https://AshCRM.ru:8443"))
        assertEquals("http://192.168.1.10:8780", Portal.normalize("http://192.168.1.10:8780"))
        assertEquals("http://10.0.2.2:8780", Portal.normalize("http://10.0.2.2:8780"))
        assertNull(Portal.normalize("http://crm.company.ru")) // clear text over the internet
        assertNull(Portal.normalize("https://crm.company.ru/path"))
        assertNull(Portal.normalize("ftp://crm.company.ru"))
        assertNull(Portal.normalize(""))
    }

    @Test fun safePath() {
        assertEquals("/team/1?x=1", Portal.safePath("/team/1?x=1"))
        assertNull(Portal.safePath("//evil.example/x"))
        assertNull(Portal.safePath("https://evil.example"))
        assertNull(Portal.safePath("/\\evil"))
    }

    @Test fun sameOrigin() {
        assertTrue(Portal.sameOrigin("https://ashcrm.ru", "https://ashcrm.ru/team/1"))
        assertTrue(Portal.sameOrigin("https://ashcrm.ru", "https://ashcrm.ru:443/x"))
        assertFalse(Portal.sameOrigin("https://ashcrm.ru", "https://s3.example/file"))
        assertFalse(Portal.sameOrigin("https://ashcrm.ru", "http://ashcrm.ru/"))
    }
}
