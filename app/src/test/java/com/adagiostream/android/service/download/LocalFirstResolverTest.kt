package com.adagiostream.android.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local-first playback URL resolution (baw.6.3 TDD). */
class LocalFirstResolverTest {

    @Test
    fun `downloaded track resolves to a file uri`() {
        val uri = LocalFirstResolver.resolve(
            localPath = "/data/music/downloads/t1.mp3",
            streamUrl = "https://music.example.com/rest/stream.view?id=t1",
        )
        assertTrue("expected a file:// uri but was $uri", uri!!.startsWith("file:"))
        assertTrue(uri.endsWith("t1.mp3"))
    }

    @Test
    fun `not-downloaded track resolves to the stream url`() {
        val uri = LocalFirstResolver.resolve(
            localPath = null,
            streamUrl = "https://music.example.com/rest/stream.view?id=t1",
        )
        assertEquals("https://music.example.com/rest/stream.view?id=t1", uri)
    }

    @Test
    fun `offline with no download and no stream resolves to null`() {
        assertNull(LocalFirstResolver.resolve(localPath = null, streamUrl = null))
    }

    @Test
    fun `local path wins even when a stream url is also available`() {
        val uri = LocalFirstResolver.resolve(
            localPath = "/m/t1.flac",
            streamUrl = "https://x/stream",
        )
        assertTrue(uri!!.startsWith("file:"))
    }

    @Test
    fun `file uri encodes spaces in the path`() {
        val uri = LocalFirstResolver.fileUri("/m/My Album/01 Track.mp3")
        assertTrue(uri.startsWith("file:"))
        assertTrue("spaces must be encoded: $uri", !uri.contains(" "))
    }
}
