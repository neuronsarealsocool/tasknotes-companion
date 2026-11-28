package com.local.tasknotescompanion.domain

import org.junit.Assert.*
import org.junit.Test

class ReminderWebLinkTest {
    @Test fun findsAddressesWithinTitleOrNoteAndPreservesTextRanges() {
        val text = "I need to go to www.x.com, then example.org/path?q=yes#now."
        val links = reminderWebLinks(text)
        assertEquals(listOf("https://www.x.com", "https://example.org/path?q=yes#now"), links.map { it.url })
        assertEquals(listOf("www.x.com", "example.org/path?q=yes#now"), links.map { text.substring(it.start, it.end) })
    }

    @Test fun supportsEscapedDotsAndFullUrlsWithBalancedParentheses() {
        val text = "Visit www\\.x.com or (https://example.com/a_(b))."
        assertEquals(listOf("https://www.x.com", "https://example.com/a_(b)"), reminderWebLinks(text).map { it.url })
    }

    @Test fun ignoresEmailAndNonWebSchemesAndInvalidAddresses() {
        assertTrue(reminderWebLinks("user@mail.example.com javascript:alert(1) file:///tmp/test ftp://example.com https://").isEmpty())
        assertTrue(reminderWebLinks("https://user:pass@example.com").isEmpty())
    }

    @Test fun preservesExplicitHttpPortsAndMultipleLinks() {
        assertEquals(listOf("http://localhost:8000/path", "https://EXAMPLE.COM:8443/a"), reminderWebLinks("http://localhost:8000/path and EXAMPLE.COM:8443/a!").map { it.url })
    }
}
