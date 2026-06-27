package com.adagiostream.android.service.player

import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Pure queue-model logic for the Navidrome E3 engine (baw.3.1).
 *
 * Covers navigation (next/previous), wrap/stop behaviour per repeat mode, and
 * shuffle (all tracks present + order changed). Deterministic where shuffle is
 * involved via a seeded [Random].
 */
class MusicQueueManagerTest {

    private lateinit var manager: MusicQueueManager

    @Before
    fun setUp() {
        manager = MusicQueueManager()
    }

    // ---- setQueue / current ----------------------------------------------

    @Test
    fun `empty queue has no current and navigation returns null`() {
        manager.setQueue(emptyList())
        assertNull(manager.current())
        assertNull(manager.next())
        assertNull(manager.previous())
        assertTrue(manager.isEmpty)
        assertEquals(0, manager.size)
    }

    @Test
    fun `setQueue starts at the requested index`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.setQueue(tracks, startIndex = 1)
        assertEquals(tracks[1], manager.current())
        assertEquals(1, manager.currentIndex)
    }

    @Test
    fun `setQueue clamps an out-of-bounds start index`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.setQueue(tracks, startIndex = 99)
        assertEquals(tracks[2], manager.current())
        manager.setQueue(tracks, startIndex = -5)
        assertEquals(tracks[0], manager.current())
    }

    // ---- next / previous, repeat OFF -------------------------------------

    @Test
    fun `next walks forward then stops at the end when repeat is off`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.setQueue(tracks, startIndex = 0)

        assertEquals(tracks[1], manager.next())
        assertEquals(tracks[2], manager.next())
        assertNull(manager.next()) // stop at end
        // current stays on the last track after a stop
        assertEquals(tracks[2], manager.current())
    }

    @Test
    fun `previous at the first track restarts current when repeat is off`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.setQueue(tracks, startIndex = 0)
        assertEquals(tracks[0], manager.previous())
        assertEquals(0, manager.currentIndex)
    }

    @Test
    fun `previous walks backward`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.setQueue(tracks, startIndex = 2)
        assertEquals(tracks[1], manager.previous())
        assertEquals(tracks[0], manager.previous())
    }

    // ---- repeat ALL -------------------------------------------------------

    @Test
    fun `next wraps from last to first when repeat is all`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.repeatMode = RepeatMode.All
        manager.setQueue(tracks, startIndex = 2)
        assertEquals(tracks[0], manager.next())
    }

    @Test
    fun `previous wraps from first to last when repeat is all`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.repeatMode = RepeatMode.All
        manager.setQueue(tracks, startIndex = 0)
        assertEquals(tracks[2], manager.previous())
    }

    // ---- repeat ONE (manual nav is a no-trap; behaves like off) ----------

    @Test
    fun `repeat one does not trap manual next`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.repeatMode = RepeatMode.One
        manager.setQueue(tracks, startIndex = 0)
        assertEquals(tracks[1], manager.next())
    }

    @Test
    fun `repeat one stops manual next at end (like off)`() {
        val tracks = TestFixtures.makeTracks(3)
        manager.repeatMode = RepeatMode.One
        manager.setQueue(tracks, startIndex = 2)
        assertNull(manager.next())
    }

    // ---- single-track edge cases -----------------------------------------

    @Test
    fun `single track next stops when repeat off`() {
        manager.setQueue(TestFixtures.makeTracks(1))
        assertNull(manager.next())
    }

    @Test
    fun `single track next repeats itself when repeat all`() {
        val tracks = TestFixtures.makeTracks(1)
        manager.repeatMode = RepeatMode.All
        manager.setQueue(tracks)
        assertEquals(tracks[0], manager.next())
    }

    // ---- shuffle: all present + order changed ----------------------------

    @Test
    fun `shuffle play order contains every track exactly once`() {
        manager.random = Random(42)
        manager.setShuffle(true)
        manager.setQueue(TestFixtures.makeTracks(10), startIndex = 0)

        val order = manager.playOrder()
        assertEquals(10, order.size)
        assertEquals((0 until 10).toList(), order.sorted())
    }

    @Test
    fun `shuffle changes the play order`() {
        manager.random = Random(42)
        manager.setShuffle(true)
        manager.setQueue(TestFixtures.makeTracks(10), startIndex = 0)

        assertFalse("shuffle order must differ from canonical", manager.playOrder() == (0 until 10).toList())
    }

    @Test
    fun `enabling shuffle keeps the current track playing first`() {
        val tracks = TestFixtures.makeTracks(10)
        manager.random = Random(7)
        manager.setQueue(tracks, startIndex = 4)
        manager.setShuffle(true)

        // Current track is pinned at the head of the shuffle order.
        assertEquals(tracks[4], manager.current())
        assertEquals(4, manager.playOrder().first())
    }

    @Test
    fun `disabling shuffle resumes natural order from the current track`() {
        val tracks = TestFixtures.makeTracks(5)
        manager.random = Random(7)
        manager.setQueue(tracks, startIndex = 0)
        manager.setShuffle(true)
        manager.next()
        val resumeTrack = manager.current()

        manager.setShuffle(false)
        // Same track is still current; order is canonical again.
        assertEquals(resumeTrack, manager.current())
        assertEquals((0 until 5).toList(), manager.playOrder())
        // Navigation now follows natural index order.
        val idx = manager.currentIndex
        if (idx < 4) assertEquals(tracks[idx + 1], manager.next())
    }

    @Test
    fun `shuffle traversal visits all tracks then stops when repeat off`() {
        val tracks = TestFixtures.makeTracks(6)
        manager.random = Random(99)
        manager.setShuffle(true)
        manager.setQueue(tracks, startIndex = 0)

        val visited = mutableListOf(manager.currentIndex)
        repeat(5) { manager.next()?.let { visited.add(manager.currentIndex) } }

        assertEquals((0 until 6).toList(), visited.sorted())
        assertNull("stops at end of shuffle order when repeat off", manager.next())
    }

    @Test
    fun `shuffle reshuffles and wraps when repeat all reaches the end`() {
        val tracks = TestFixtures.makeTracks(4)
        manager.random = Random(5)
        manager.repeatMode = RepeatMode.All
        manager.setShuffle(true)
        manager.setQueue(tracks, startIndex = 0)

        // Walk a full lap plus one — must keep yielding tracks (never null).
        repeat(tracks.size + 1) { assertTrue(manager.next() != null) }
    }

    // ---- repeat mode cycle ------------------------------------------------

    @Test
    fun `cycleRepeatMode goes off to all to one to off`() {
        assertEquals(RepeatMode.Off, manager.repeatMode)
        manager.cycleRepeatMode(); assertEquals(RepeatMode.All, manager.repeatMode)
        manager.cycleRepeatMode(); assertEquals(RepeatMode.One, manager.repeatMode)
        manager.cycleRepeatMode(); assertEquals(RepeatMode.Off, manager.repeatMode)
    }
}
