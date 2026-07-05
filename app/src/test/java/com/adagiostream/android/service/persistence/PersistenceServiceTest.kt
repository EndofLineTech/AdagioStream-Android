package com.adagiostream.android.service.persistence

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
}
