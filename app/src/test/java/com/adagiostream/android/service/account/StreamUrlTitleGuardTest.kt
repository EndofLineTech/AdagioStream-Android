package com.adagiostream.android.service.account

import com.adagiostream.android.service.account.AccountManager.Companion.isStreamUrlFilename
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers beads_adagio-59p.3.5: a candidate track title equal to the stream
 * URL's last path component (e.g. "20998.ts") must be rejected at the choke
 * point where titles enter now-playing metadata.
 */
class StreamUrlTitleGuardTest {

    @Test
    fun `title equal to the stream filename is rejected`() {
        assertTrue(isStreamUrlFilename("20998.ts", "http://host.example/live/20998.ts"))
    }

    @Test
    fun `query string and fragment do not hide the filename`() {
        assertTrue(isStreamUrlFilename("20998.ts", "http://host.example/live/20998.ts?token=abc"))
        assertTrue(isStreamUrlFilename("20998.ts", "https://host.example/20998.ts#frag"))
    }

    @Test
    fun `surrounding whitespace in the title still matches`() {
        assertTrue(isStreamUrlFilename(" 20998.ts ", "http://host.example/live/20998.ts"))
    }

    @Test
    fun `comparison is case-insensitive`() {
        assertTrue(isStreamUrlFilename("20998.TS", "http://host.example/live/20998.ts"))
    }

    @Test
    fun `real track titles pass through`() {
        assertFalse(isStreamUrlFilename("The Sign", "http://host.example/live/20998.ts"))
        assertFalse(isStreamUrlFilename("20998", "http://host.example/live/20998.ts"))
    }

    @Test
    fun `host-only URLs never reject anything`() {
        assertFalse(isStreamUrlFilename("host.example", "http://host.example"))
        assertFalse(isStreamUrlFilename("", "http://host.example/"))
    }

    @Test
    fun `trailing slash resolves to the last real component`() {
        assertTrue(isStreamUrlFilename("live", "http://host.example/live/"))
    }
}
