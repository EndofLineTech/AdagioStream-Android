package com.adagiostream.android.service.player

import com.adagiostream.android.service.persistence.LastPlayed
import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing coverage for [ResumptionPolicy] (baw.10 — bug: library-track resume
 * fell through to channels because `onPlaybackResumption` only ever resolved
 * against `accountManager.channels`). These tests lock the fix: a persisted
 * [LastPlayed.LibraryTrack] must route to [ResumptionPolicy.Plan.ResumeLibraryTrack],
 * a persisted [LastPlayed.Channel] keeps the exact pre-existing channel
 * behaviour, and every "can't actually resume" case degrades to
 * [ResumptionPolicy.Plan.NothingToResume] rather than silently doing nothing
 * useful with stale/partial data.
 */
class ResumptionPolicyTest {

    private val channel = TestFixtures.makeChannel(id = "chan-1", name = "Channel One")

    @Test
    fun `null last played resumes nothing`() {
        val plan = ResumptionPolicy.resolve(lastPlayed = null, channels = listOf(channel), hasSubsonicApi = true)
        assertEquals(ResumptionPolicy.Plan.NothingToResume, plan)
    }

    // ---- channel routing (pre-existing behaviour, unchanged) --------------

    @Test
    fun `channel last played resumes the matching channel`() {
        val plan = ResumptionPolicy.resolve(
            lastPlayed = LastPlayed.Channel("chan-1"),
            channels = listOf(channel),
            hasSubsonicApi = false,
        )
        assertEquals(ResumptionPolicy.Plan.ResumeChannel(channel), plan)
    }

    @Test
    fun `channel last played with no matching channel resumes nothing`() {
        val plan = ResumptionPolicy.resolve(
            lastPlayed = LastPlayed.Channel("gone"),
            channels = listOf(channel),
            hasSubsonicApi = false,
        )
        assertEquals(ResumptionPolicy.Plan.NothingToResume, plan)
    }

    // ---- library track routing (baw.10 fix) --------------------------------

    @Test
    fun `library track last played resumes the exact track, queue and position`() {
        val queue = TestFixtures.makeTracks(3)
        val plan = ResumptionPolicy.resolve(
            lastPlayed = LastPlayed.LibraryTrack(queue = queue, index = 1, positionMs = 42_000L, albumTitle = "Album X"),
            channels = listOf(channel), // a channel exists too — must NOT win over the library track
            hasSubsonicApi = true,
        )
        val resumed = plan as ResumptionPolicy.Plan.ResumeLibraryTrack
        assertEquals(queue, resumed.queue)
        assertEquals(1, resumed.index)
        assertEquals(42_000L, resumed.positionMs)
        assertEquals("Album X", resumed.albumTitle)
    }

    @Test
    fun `library track last played without a resolvable Subsonic api resumes nothing`() {
        // Can't build a stream URL without the account/API — the pre-fix bug's
        // silent-failure case must degrade safely, not crash or half-resume.
        val plan = ResumptionPolicy.resolve(
            lastPlayed = LastPlayed.LibraryTrack(queue = TestFixtures.makeTracks(2), index = 0, positionMs = 0L),
            channels = emptyList(),
            hasSubsonicApi = false,
        )
        assertEquals(ResumptionPolicy.Plan.NothingToResume, plan)
    }

    @Test
    fun `library track last played with an out-of-range index resumes nothing`() {
        val plan = ResumptionPolicy.resolve(
            lastPlayed = LastPlayed.LibraryTrack(queue = TestFixtures.makeTracks(2), index = 5, positionMs = 0L),
            channels = emptyList(),
            hasSubsonicApi = true,
        )
        assertEquals(ResumptionPolicy.Plan.NothingToResume, plan)
    }

    @Test
    fun `library track last played with an empty queue resumes nothing`() {
        val plan = ResumptionPolicy.resolve(
            lastPlayed = LastPlayed.LibraryTrack(queue = emptyList(), index = 0, positionMs = 0L),
            channels = emptyList(),
            hasSubsonicApi = true,
        )
        assertTrue(plan is ResumptionPolicy.Plan.NothingToResume)
    }
}
