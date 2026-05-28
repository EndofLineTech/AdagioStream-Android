package com.adagiostream.android.service.parsing

import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class M3UParserTest {

    private lateinit var parser: M3UParser

    @Before
    fun setUp() {
        // OkHttpClient is only used by parse(url), not parseContent() — safe to pass any instance
        parser = M3UParser(okhttp3.OkHttpClient())
    }

    @Test
    fun `parseContent returns channels from minimal M3U`() {
        val channels = parser.parseContent(TestFixtures.MINIMAL_M3U)
        assertEquals(1, channels.size)
        assertEquals("Test Channel", channels[0].name)
        assertEquals("http://example.com/stream1", channels[0].streamURL)
    }

    @Test
    fun `parseContent extracts tvg-id attribute`() {
        val channels = parser.parseContent(TestFixtures.FULL_ATTRIBUTES_M3U)
        assertEquals("ch1", channels[0].epgChannelID)
        assertEquals("ch2", channels[1].epgChannelID)
    }

    @Test
    fun `parseContent extracts tvg-name as channel name`() {
        val channels = parser.parseContent(TestFixtures.FULL_ATTRIBUTES_M3U)
        assertEquals("Channel One", channels[0].name)
        assertEquals("Channel Two", channels[1].name)
    }

    @Test
    fun `parseContent extracts tvg-logo`() {
        val channels = parser.parseContent(TestFixtures.FULL_ATTRIBUTES_M3U)
        assertEquals("http://logo.com/1.png", channels[0].logoURL)
    }

    @Test
    fun `parseContent extracts group-title`() {
        val channels = parser.parseContent(TestFixtures.FULL_ATTRIBUTES_M3U)
        assertEquals("News", channels[0].group)
        assertEquals("Sports", channels[1].group)
    }

    @Test
    fun `parseContent returns empty list for empty M3U`() {
        val channels = parser.parseContent(TestFixtures.EMPTY_M3U)
        assertTrue(channels.isEmpty())
    }

    @Test
    fun `parseContent works without EXTM3U header`() {
        val channels = parser.parseContent(TestFixtures.NO_HEADER_M3U)
        assertEquals(1, channels.size)
        assertEquals("No Header Channel", channels[0].name)
    }

    @Test
    fun `parseContent skips entry without stream URL`() {
        val channels = parser.parseContent(TestFixtures.MISSING_URL_M3U)
        assertTrue(channels.isEmpty())
    }

    @Test
    fun `parseContent uses Uncategorized for missing group-title`() {
        val channels = parser.parseContent(TestFixtures.MINIMAL_M3U)
        assertEquals("Uncategorized", channels[0].group)
    }

    @Test
    fun `parseContent uses Uncategorized for blank group-title`() {
        val channels = parser.parseContent(TestFixtures.BLANK_ATTRIBUTES_M3U)
        assertEquals("Uncategorized", channels[0].group)
    }

    @Test
    fun `parseContent uses display name when tvg-name is blank`() {
        val channels = parser.parseContent(TestFixtures.BLANK_ATTRIBUTES_M3U)
        assertEquals("Fallback Name", channels[0].name)
    }

    @Test
    fun `parseContent sets null for blank tvg-id`() {
        val channels = parser.parseContent(TestFixtures.BLANK_ATTRIBUTES_M3U)
        assertNull(channels[0].epgChannelID)
    }

    @Test
    fun `parseContent sets null for blank tvg-logo`() {
        val channels = parser.parseContent(TestFixtures.BLANK_ATTRIBUTES_M3U)
        assertNull(channels[0].logoURL)
    }

    @Test
    fun `parseContent generates unique IDs`() {
        val channels = parser.parseContent(TestFixtures.FULL_ATTRIBUTES_M3U)
        assertEquals(2, channels.size)
        assertTrue("IDs should be unique", channels[0].id != channels[1].id)
    }

    @Test
    fun `parseContent handles multiple channels`() {
        val channels = parser.parseContent(TestFixtures.FULL_ATTRIBUTES_M3U)
        assertEquals(2, channels.size)
    }

    @Test
    fun `parseContent handles extra blank lines between entries`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Channel A

            http://example.com/a

            #EXTINF:-1,Channel B
            http://example.com/b
        """.trimIndent()
        val channels = parser.parseContent(m3u)
        assertEquals(2, channels.size)
    }

    @Test
    fun `parseContent handles comment lines between EXTINF and URL`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Channel A
            #EXTVLCOPT:some-option
            http://example.com/a
        """.trimIndent()
        val channels = parser.parseContent(m3u)
        assertEquals(1, channels.size)
        assertEquals("http://example.com/a", channels[0].streamURL)
    }

    @Test
    fun `parseContent returns empty for completely empty input`() {
        val channels = parser.parseContent("")
        assertTrue(channels.isEmpty())
    }

    @Test
    fun `parseContent BufferedReader overload streams large playlist without loading whole file`() {
        // Generate a ~1.5 MB M3U and stream it through the new BufferedReader path.
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        val target = 1_500_000
        var i = 0
        while (sb.length < target) {
            sb.append(
                "#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"Channel $i\" " +
                    "tvg-logo=\"http://logos.example.com/${i % 100}.png\" " +
                    "group-title=\"Group ${i % 50}\",Channel $i\n",
            )
            sb.append("http://stream.example.com/live/$i.ts\n")
            i++
        }
        val reader = java.io.BufferedReader(java.io.StringReader(sb.toString()))

        val channels = parser.parseContent(reader)

        assertTrue("Should parse many channels", channels.size > 1000)
        assertEquals("Channel 0", channels[0].name)
        assertEquals("http://stream.example.com/live/0.ts", channels[0].streamURL)
        assertEquals("Group 0", channels[0].group)
    }

    @Test
    fun `parseContent BufferedReader handles EXTINF with no following URL`() {
        // An orphan EXTINF at end-of-file should not crash.
        val m3u = """
            #EXTM3U
            #EXTINF:-1,First
            http://example.com/first
            #EXTINF:-1,Orphan
        """.trimIndent()
        val channels = parser.parseContent(java.io.BufferedReader(java.io.StringReader(m3u)))
        assertEquals(1, channels.size)
        assertEquals("First", channels[0].name)
    }
}
