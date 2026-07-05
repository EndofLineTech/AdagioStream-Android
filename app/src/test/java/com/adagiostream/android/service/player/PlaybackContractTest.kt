package com.adagiostream.android.service.player

import androidx.media3.common.C
import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the live-vs-library playback contract (baw.3.7 DESIGN GATE).
 *
 * The Radio / null cases here are the REGRESSION GUARD for live IPTV playback:
 * the live path has always emitted isSeekable=false, durationUs=TIME_UNSET,
 * isDynamic=true, and treated EndReached as a retryable error. VLCSessionPlayer
 * and VLCPlayerWrapper now read those decisions from [PlaybackContract], so if a
 * future change flips any of them for live, these tests fail in CI.
 */
class PlaybackContractTest {

    private val channel = TestFixtures.makeChannel(id = "ch-1", name = "Live FM")
    private val radio = PlaybackSource.Radio(channel)

    private fun library(durationSeconds: Int? = 180): PlaybackSource.Library =
        PlaybackSource.Library(
            queue = listOf(TestFixtures.makeTrack(id = "t-1", duration = durationSeconds)),
            index = 0,
        )

    // ---- isSeekable -------------------------------------------------------

    @Test
    fun `live radio is never seekable`() {
        assertFalse(PlaybackContract.isSeekable(radio))
    }

    @Test
    fun `null source defaults to non-seekable (conservative live default)`() {
        assertFalse(PlaybackContract.isSeekable(null))
    }

    @Test
    fun `library track is seekable`() {
        assertTrue(PlaybackContract.isSeekable(library()))
    }

    // ---- durationUs -------------------------------------------------------

    @Test
    fun `live radio reports TIME_UNSET duration`() {
        assertEquals(C.TIME_UNSET, PlaybackContract.durationUs(radio))
    }

    @Test
    fun `null source reports TIME_UNSET duration`() {
        assertEquals(C.TIME_UNSET, PlaybackContract.durationUs(null))
    }

    @Test
    fun `library track reports real duration in microseconds`() {
        assertEquals(180_000_000L, PlaybackContract.durationUs(library(durationSeconds = 180)))
    }

    @Test
    fun `library track with unknown duration reports TIME_UNSET`() {
        assertEquals(C.TIME_UNSET, PlaybackContract.durationUs(library(durationSeconds = null)))
    }

    @Test
    fun `library track with zero duration reports TIME_UNSET`() {
        assertEquals(C.TIME_UNSET, PlaybackContract.durationUs(library(durationSeconds = 0)))
    }

    // ---- isDynamic --------------------------------------------------------

    @Test
    fun `live radio playlist is dynamic`() {
        assertTrue(PlaybackContract.isDynamic(radio))
    }

    @Test
    fun `null source is dynamic (conservative live default)`() {
        assertTrue(PlaybackContract.isDynamic(null))
    }

    @Test
    fun `library track window is not dynamic`() {
        assertFalse(PlaybackContract.isDynamic(library()))
    }

    // ---- endReachedPolicy -------------------------------------------------

    @Test
    fun `live radio EndReached retries as error`() {
        assertEquals(EndReachedPolicy.RetryAsError, PlaybackContract.endReachedPolicy(radio))
    }

    @Test
    fun `null source EndReached retries as error (conservative live default)`() {
        assertEquals(EndReachedPolicy.RetryAsError, PlaybackContract.endReachedPolicy(null))
    }

    @Test
    fun `library EndReached auto-advances`() {
        assertEquals(EndReachedPolicy.AutoAdvance, PlaybackContract.endReachedPolicy(library()))
    }
}
