package com.adagiostream.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateUtilsTest {

    @Test
    fun `parseXmltvDate parses standard format`() {
        // 2025-01-01 12:00:00 UTC = 1735732800000
        val result = DateUtils.parseXmltvDate("20250101120000 +0000")
        assertEquals(1735732800000L, result)
    }

    @Test
    fun `parseXmltvDate parses with positive offset`() {
        // 2025-01-01 12:00:00 +0100 = 2025-01-01 11:00:00 UTC = 1735729200000
        val result = DateUtils.parseXmltvDate("20250101120000 +0100")
        assertEquals(1735729200000L, result)
    }

    @Test
    fun `parseXmltvDate parses with negative offset`() {
        // 2025-01-01 12:00:00 -0500 = 2025-01-01 17:00:00 UTC = 1735750800000
        val result = DateUtils.parseXmltvDate("20250101120000 -0500")
        assertEquals(1735750800000L, result)
    }

    @Test
    fun `parseXmltvDate returns 0 for empty string`() {
        assertEquals(0L, DateUtils.parseXmltvDate(""))
    }

    @Test
    fun `parseXmltvDate returns 0 for garbage input`() {
        assertEquals(0L, DateUtils.parseXmltvDate("not-a-date"))
    }

    @Test
    fun `parseXmltvDate returns 0 for partial format`() {
        assertEquals(0L, DateUtils.parseXmltvDate("20250101"))
    }

    @Test
    fun `formatTime returns non-empty string for valid millis`() {
        val result = DateUtils.formatTime(1735732800000L)
        assertTrue("Expected non-empty time string", result.isNotBlank())
    }

    @Test
    fun `formatTime contains AM or PM`() {
        val result = DateUtils.formatTime(1735732800000L)
        assertTrue(
            "Expected AM or PM in '$result'",
            result.contains("AM", ignoreCase = true) || result.contains("PM", ignoreCase = true)
        )
    }

    @Test
    fun `formatTime contains colon separator`() {
        val result = DateUtils.formatTime(1735732800000L)
        assertTrue("Expected ':' in '$result'", result.contains(":"))
    }

    @Test
    fun `parseXmltvDate round-trips through formatTime`() {
        val millis = DateUtils.parseXmltvDate("20250615143000 +0000")
        assertTrue("Should parse to positive millis", millis > 0)
        val formatted = DateUtils.formatTime(millis)
        assertTrue("Formatted time should not be empty", formatted.isNotBlank())
    }
}
