package com.adagiostream.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    // --- requireHttpUrl() tests (needs Android Uri) ---

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
}
