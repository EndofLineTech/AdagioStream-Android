package com.adagiostream.android.service.persistence

import com.adagiostream.android.service.player.ResumptionPolicy
import com.adagiostream.android.testutil.TestFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Last-played round-trip coverage (baw.10): the bug was that resume only ever
 * persisted/read a channel id, so a paused library track had nowhere to
 * record itself. These tests lock the extended [LastPlayed] persistence:
 * both variants round-trip, a legacy plain-text channel id (pre-baw.10 file
 * format) still loads, and there's no last-played entry before anything is
 * saved.
 */
@RunWith(RobolectricTestRunner::class)
class PersistenceServiceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val service = PersistenceService(RuntimeEnvironment.getApplication(), json)

    @Test
    fun `no last played before anything is saved`() = runTest {
        assertNull(service.loadLastPlayed())
    }

    @Test
    fun `channel last played round-trips`() = runTest {
        service.saveLastPlayed(LastPlayed.Channel("chan-42"))

        val loaded = service.loadLastPlayed()

        assertEquals(LastPlayed.Channel("chan-42"), loaded)
    }

    @Test
    fun `library track last played round-trips queue, index and position`() = runTest {
        val queue = TestFixtures.makeTracks(3)
        service.saveLastPlayed(LastPlayed.LibraryTrack(queue = queue, index = 2, positionMs = 91_500L, albumTitle = "Album X"))

        val loaded = service.loadLastPlayed()

        assertTrue(loaded is LastPlayed.LibraryTrack)
        val track = loaded as LastPlayed.LibraryTrack
        assertEquals(queue, track.queue)
        assertEquals(2, track.index)
        assertEquals(91_500L, track.positionMs)
        assertEquals("Album X", track.albumTitle)
    }

    @Test
    fun `saving a library track then a channel overwrites — only the latest survives`() = runTest {
        service.saveLastPlayed(LastPlayed.LibraryTrack(queue = TestFixtures.makeTracks(1), index = 0, positionMs = 5_000L))
        service.saveLastPlayed(LastPlayed.Channel("chan-1"))

        assertEquals(LastPlayed.Channel("chan-1"), service.loadLastPlayed())
    }

    @Test
    fun `legacy plain-text channel id file still loads as a channel`() = runTest {
        // Pre-baw.10 files stored a bare channel id with no JSON envelope.
        val legacyFile = java.io.File(RuntimeEnvironment.getApplication().filesDir, "last_played.txt")
        legacyFile.writeText("legacy-channel-id")

        val loaded = service.loadLastPlayed()

        assertEquals(LastPlayed.Channel("legacy-channel-id"), loaded)
    }

    @Test
    fun `truncated JSON does not throw and degrades to nothing-to-resume`() = runTest {
        // baw.14: a partial write (process death mid-save) must never crash
        // resume — decode fails, the raw text falls back to Channel(garbage),
        // and the policy resolves that to NothingToResume (no channel matches).
        val file = java.io.File(RuntimeEnvironment.getApplication().filesDir, "last_played.txt")
        file.writeText("""{"type":"library_track","queue":[{"id":"tr""")

        val loaded = service.loadLastPlayed()

        assertTrue(loaded is LastPlayed.Channel)
        val plan = ResumptionPolicy.resolve(
            lastPlayed = loaded,
            channels = emptyList(),
            hasSubsonicApi = true,
        )
        assertEquals(ResumptionPolicy.Plan.NothingToResume, plan)
    }

    // ---- position sidecar (baw.14) -----------------------------------------

    @Test
    fun `position sidecar overlays index and position onto the saved library queue`() = runTest {
        val queue = TestFixtures.makeTracks(3)
        service.saveLastPlayed(LastPlayed.LibraryTrack(queue = queue, index = 0, positionMs = 0L))

        service.saveLastPlayedPosition(index = 2, positionMs = 73_000L)
        val loaded = service.loadLastPlayed() as LastPlayed.LibraryTrack

        assertEquals(queue, loaded.queue) // queue untouched by position-only writes
        assertEquals(2, loaded.index)
        assertEquals(73_000L, loaded.positionMs)
    }

    @Test
    fun `full save clears a stale position sidecar`() = runTest {
        service.saveLastPlayed(LastPlayed.LibraryTrack(queue = TestFixtures.makeTracks(3), index = 0, positionMs = 0L))
        service.saveLastPlayedPosition(index = 2, positionMs = 73_000L)

        // New queue full-save — the old sidecar must not leak into it.
        val newQueue = TestFixtures.makeTracks(2)
        service.saveLastPlayed(LastPlayed.LibraryTrack(queue = newQueue, index = 1, positionMs = 5_000L))
        val loaded = service.loadLastPlayed() as LastPlayed.LibraryTrack

        assertEquals(newQueue, loaded.queue)
        assertEquals(1, loaded.index)
        assertEquals(5_000L, loaded.positionMs)
    }

    @Test
    fun `corrupt position sidecar is ignored in favour of full-save values`() = runTest {
        service.saveLastPlayed(LastPlayed.LibraryTrack(queue = TestFixtures.makeTracks(2), index = 1, positionMs = 9_000L))
        java.io.File(RuntimeEnvironment.getApplication().filesDir, "last_played_pos.txt").writeText("not numbers")

        val loaded = service.loadLastPlayed() as LastPlayed.LibraryTrack

        assertEquals(1, loaded.index)
        assertEquals(9_000L, loaded.positionMs)
    }
}
