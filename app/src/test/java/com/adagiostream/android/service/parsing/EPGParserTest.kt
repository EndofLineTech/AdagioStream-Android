package com.adagiostream.android.service.parsing

import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EPGParserTest {

    private lateinit var parser: EPGParser

    @Before
    fun setUp() {
        parser = EPGParser(okhttp3.OkHttpClient())
    }

    @Test
    fun `parseContent returns entries for minimal XML`() {
        val result = parser.parseContent(TestFixtures.MINIMAL_EPG_XML)
        assertEquals(1, result.size)
        assertTrue(result.containsKey("ch1"))
        assertEquals("Test Show", result["ch1"]!![0].title)
    }

    @Test
    fun `parseContent groups entries by channel`() {
        val result = parser.parseContent(TestFixtures.MULTI_CHANNEL_EPG_XML)
        assertEquals(2, result.size)
        assertEquals(2, result["ch1"]!!.size)
        assertEquals(1, result["ch2"]!!.size)
    }

    @Test
    fun `parseContent extracts description`() {
        val result = parser.parseContent(TestFixtures.MULTI_CHANNEL_EPG_XML)
        assertEquals("Description A", result["ch1"]!![0].description)
    }

    @Test
    fun `parseContent sets null description when missing`() {
        val result = parser.parseContent(TestFixtures.MULTI_CHANNEL_EPG_XML)
        assertNull(result["ch1"]!![1].description)
    }

    @Test
    fun `parseContent parses start and end timestamps`() {
        val result = parser.parseContent(TestFixtures.MINIMAL_EPG_XML)
        val entry = result["ch1"]!![0]
        assertTrue("Start should be positive", entry.start > 0)
        assertTrue("End should be positive", entry.end > 0)
        assertTrue("End should be after start", entry.end > entry.start)
    }

    @Test
    fun `parseContent returns empty map for empty TV element`() {
        val result = parser.parseContent(TestFixtures.EMPTY_EPG_XML)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseContent skips programme without title`() {
        val result = parser.parseContent(TestFixtures.MISSING_TITLE_EPG_XML)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseContent sets correct channelID on entries`() {
        val result = parser.parseContent(TestFixtures.MULTI_CHANNEL_EPG_XML)
        result["ch1"]!!.forEach { assertEquals("ch1", it.channelID) }
        result["ch2"]!!.forEach { assertEquals("ch2", it.channelID) }
    }

    @Test
    fun `parseContent handles programme with all fields`() {
        val result = parser.parseContent(TestFixtures.MULTI_CHANNEL_EPG_XML)
        val entry = result["ch2"]!![0]
        assertEquals("ch2", entry.channelID)
        assertEquals("Show C", entry.title)
        assertEquals("Description C", entry.description)
        assertNotNull(entry.start)
        assertNotNull(entry.end)
    }

    @Test
    fun `parseContent start and end are one hour apart for one-hour programme`() {
        val result = parser.parseContent(TestFixtures.MINIMAL_EPG_XML)
        val entry = result["ch1"]!![0]
        val oneHourMs = 3_600_000L
        assertEquals(oneHourMs, entry.end - entry.start)
    }

    @Test
    fun `parseContent handles missing stop attribute gracefully`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme channel="ch1" start="20250101120000 +0000">
                <title>No Stop</title>
              </programme>
            </tv>
        """.trimIndent()
        val result = parser.parseContent(xml)
        assertTrue("Should skip entry without stop attribute", result.isEmpty())
    }

    @Test
    fun `parseContent handles missing channel attribute gracefully`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000">
                <title>No Channel</title>
              </programme>
            </tv>
        """.trimIndent()
        val result = parser.parseContent(xml)
        assertTrue("Should skip entry without channel attribute", result.isEmpty())
    }

    @Test
    fun `parseContent preserves programme order within channel`() {
        val result = parser.parseContent(TestFixtures.MULTI_CHANNEL_EPG_XML)
        val ch1 = result["ch1"]!!
        assertEquals("Show A", ch1[0].title)
        assertEquals("Show B", ch1[1].title)
    }

    @Test
    fun `parseContent handles large number of programmes`() {
        val programmes = (1..100).joinToString("\n") { i ->
            """
              <programme channel="ch1" start="20250101${"%02d".format(i % 24)}0000 +0000" stop="20250101${"%02d".format((i % 24) + 1)}0000 +0000">
                <title>Show $i</title>
              </programme>
            """.trimIndent()
        }
        val xml = """<?xml version="1.0" encoding="UTF-8"?><tv>$programmes</tv>"""
        val result = parser.parseContent(xml)
        // Some entries may have invalid hours (24+), but we care it doesn't crash
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `parseContent Reader overload streams large XML without buffering whole document`() {
        // Generate a ~2 MB EPG XML and stream it via Reader (the new streaming path).
        // This exercises the same code path as the production parse(url) flow.
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?><tv>""")
        val target = 2 * 1024 * 1024
        var i = 0
        while (sb.length < target) {
            val hour = "%02d".format(i % 23)
            val nextHour = "%02d".format((i % 23) + 1)
            sb.append(
                "<programme channel=\"ch${i % 5}\" start=\"20250101${hour}0000 +0000\" " +
                    "stop=\"20250101${nextHour}0000 +0000\">" +
                    "<title>Show $i</title><desc>${"d".repeat(64)}</desc></programme>",
            )
            i++
        }
        sb.append("</tv>")
        val reader = java.io.StringReader(sb.toString())

        val result = parser.parseContent(reader)

        assertTrue("Expected entries from streamed XML", result.isNotEmpty())
        // Some entries from each channel id we used (ch0..ch4)
        assertTrue("Should have multiple channels", result.size >= 2)
    }

    @Test
    fun `parseContent String overload still works after refactor`() {
        val result = parser.parseContent(TestFixtures.MINIMAL_EPG_XML)
        assertEquals(1, result.size)
        assertTrue(result.containsKey("ch1"))
    }
}
