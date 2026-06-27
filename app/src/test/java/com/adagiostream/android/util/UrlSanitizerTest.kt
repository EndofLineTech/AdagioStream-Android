package com.adagiostream.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSanitizerTest {

    // --- redact() tests (pure string operations) ---

    @Test
    fun `redact replaces username query param`() {
        val input = "http://example.com?username=admin&action=get"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?username=****&action=get", result)
    }

    @Test
    fun `redact replaces password query param`() {
        val input = "http://example.com?password=secret123"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?password=****", result)
    }

    @Test
    fun `redact replaces token query param`() {
        val input = "http://example.com?token=abc123"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?token=****", result)
    }

    @Test
    fun `redact replaces key query param`() {
        val input = "http://example.com?key=myapikey"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?key=****", result)
    }

    @Test
    fun `redact replaces multiple sensitive params`() {
        val input = "http://example.com?username=admin&password=secret"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?username=****&password=****", result)
    }

    @Test
    fun `redact replaces path credentials`() {
        val input = "http://example.com/live/user1/pass1/12345.ts"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com/live/****/****/12345.ts", result)
    }

    @Test
    fun `redact is case insensitive for query params`() {
        val input = "http://example.com?USERNAME=admin&Password=secret"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?USERNAME=****&Password=****", result)
    }

    @Test
    fun `redact leaves safe URLs unchanged`() {
        val input = "http://example.com/stream.m3u"
        val result = UrlSanitizer.redact(input)
        assertEquals(input, result)
    }

    @Test
    fun `redact handles pass query param`() {
        val input = "http://example.com?user=admin&pass=secret"
        val result = UrlSanitizer.redact(input)
        assertEquals("http://example.com?user=****&pass=****", result)
    }

    @Test
    fun `redact leaves non-sensitive query params intact`() {
        val input = "http://example.com?action=get_live_streams&type=m3u"
        val result = UrlSanitizer.redact(input)
        assertEquals(input, result)
    }

    // --- Subsonic auth params (baw.1.9) ---

    @Test
    fun `redact redacts Subsonic u param but preserves other single-char params`() {
        // u= should be redacted; id= should NOT (it doesn't match the [?&](u|t|s)= pattern)
        val input = "http://navidrome.example.com/rest/ping.view?u=alice&t=abc123&s=def456&id=42"
        val result = UrlSanitizer.redact(input)
        assertEquals(
            "http://navidrome.example.com/rest/ping.view?u=****&t=****&s=****&id=42",
            result,
        )
    }

    @Test
    fun `redact redacts Subsonic u t s params appearing after amp`() {
        val input = "http://example.com/rest/stream.view?c=AdagioStream&u=bob&t=tokenXYZ&s=saltABC&f=json"
        val result = UrlSanitizer.redact(input)
        assertEquals(
            "http://example.com/rest/stream.view?c=AdagioStream&u=****&t=****&s=****&f=json",
            result,
        )
    }

    @Test
    fun `redact does not over-match params that start with u t or s but are longer`() {
        // "type=", "size=", "url=" should NOT be redacted — only exact [?&](u|t|s)= matches
        val input = "http://example.com?type=newest&size=10&url=foo"
        val result = UrlSanitizer.redact(input)
        assertEquals(input, result)
    }

    @Test
    fun `redact handles Subsonic URL starting with question-mark u param`() {
        val input = "http://example.com/rest/ping.view?u=alice&v=1.16.1"
        val result = UrlSanitizer.redact(input)
        assertEquals(
            "http://example.com/rest/ping.view?u=****&v=1.16.1",
            result,
        )
    }

    // --- requireHttpUrl() tests ---

    @Test
    fun `requireHttpUrl accepts http`() {
        UrlSanitizer.requireHttpUrl("http://example.com")
    }

    @Test
    fun `requireHttpUrl accepts https`() {
        UrlSanitizer.requireHttpUrl("https://example.com")
    }

    @Test
    fun `requireHttpUrl rejects ftp`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlSanitizer.requireHttpUrl("ftp://example.com")
        }
    }

    @Test
    fun `requireHttpUrl rejects empty string`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlSanitizer.requireHttpUrl("")
        }
    }

    // --- isHttpUrl() tests ---

    @Test
    fun `isHttpUrl returns true for http`() {
        assertTrue(UrlSanitizer.isHttpUrl("http://example.com/logo.png"))
    }

    @Test
    fun `isHttpUrl returns true for https`() {
        assertTrue(UrlSanitizer.isHttpUrl("https://example.com/logo.png"))
    }

    @Test
    fun `isHttpUrl returns false for ftp`() {
        assertFalse(UrlSanitizer.isHttpUrl("ftp://example.com/file"))
    }

    @Test
    fun `isHttpUrl returns false for file scheme`() {
        assertFalse(UrlSanitizer.isHttpUrl("file:///etc/passwd"))
    }

    @Test
    fun `isHttpUrl returns false for content scheme`() {
        assertFalse(UrlSanitizer.isHttpUrl("content://com.example/data"))
    }

    @Test
    fun `isHttpUrl returns false for empty string`() {
        assertFalse(UrlSanitizer.isHttpUrl(""))
    }

    @Test
    fun `isHttpUrl returns false for garbage input`() {
        assertFalse(UrlSanitizer.isHttpUrl("not a url at all"))
    }
}
