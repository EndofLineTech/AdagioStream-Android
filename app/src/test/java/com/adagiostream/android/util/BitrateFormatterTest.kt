package com.adagiostream.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BitrateFormatterTest {

    @Test
    fun `zero returns empty string`() {
        assertEquals("", BitrateFormatter.format(0f))
    }

    @Test
    fun `negative returns empty string`() {
        assertEquals("", BitrateFormatter.format(-1f))
    }

    @Test
    fun `small kbps returns kbps format`() {
        assertEquals("128 kbps", BitrateFormatter.format(128f))
    }

    @Test
    fun `fractional kbps is rounded`() {
        assertEquals("320 kbps", BitrateFormatter.format(320.4f))
    }

    @Test
    fun `exactly 1000 returns Mbps format`() {
        assertEquals("1.0 Mbps", BitrateFormatter.format(1000f))
    }

    @Test
    fun `above 1000 returns Mbps format`() {
        assertEquals("1.5 Mbps", BitrateFormatter.format(1500f))
    }

    @Test
    fun `large bitrate returns Mbps format`() {
        assertEquals("3.2 Mbps", BitrateFormatter.format(3200f))
    }

    @Test
    fun `just below 1000 returns kbps format`() {
        assertEquals("999 kbps", BitrateFormatter.format(999f))
    }
}
