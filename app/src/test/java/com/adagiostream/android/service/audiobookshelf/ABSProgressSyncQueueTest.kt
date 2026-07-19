package com.adagiostream.android.service.audiobookshelf

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Offline progress queue semantics (beads_adagio-59p.1.5): last-writer-wins
 * dedup per (libraryItemId, episodeId), flush clears ONLY the sent snapshot,
 * pendingPosition seeds resume, and the queue round-trips through disk.
 * Deterministic — explicit lastUpdate stamps, mocked API, no wall clock.
 */
class ABSProgressSyncQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun queueFile(): File = File(tmp.root, "abs_progress_queue.json")

    private fun update(
        itemId: String,
        currentTime: Double,
        lastUpdate: Long,
        episodeId: String? = null,
    ) = AbsProgressUpdate.of(
        libraryItemId = itemId,
        episodeId = episodeId,
        currentTime = currentTime,
        duration = 1000.0,
        lastUpdate = lastUpdate,
    )

    // ---- dedup -------------------------------------------------------------

    @Test
    fun `enqueue dedups per item keeping the newest lastUpdate`() {
        val queue = ABSProgressSyncQueue(queueFile())
        queue.enqueue(update("book1", currentTime = 10.0, lastUpdate = 1_000))
        queue.enqueue(update("book1", currentTime = 50.0, lastUpdate = 2_000))
        assertEquals(1, queue.snapshot().size)
        assertEquals(50.0, queue.pendingPosition("book1")!!, 1e-9)
    }

    @Test
    fun `a stale update never replaces a newer queued entry`() {
        val queue = ABSProgressSyncQueue(queueFile())
        queue.enqueue(update("book1", currentTime = 50.0, lastUpdate = 2_000))
        queue.enqueue(update("book1", currentTime = 10.0, lastUpdate = 1_000))
        assertEquals(50.0, queue.pendingPosition("book1")!!, 1e-9)
    }

    @Test
    fun `episodes dedup independently of their book`() {
        val queue = ABSProgressSyncQueue(queueFile())
        queue.enqueue(update("pod1", currentTime = 5.0, lastUpdate = 1_000, episodeId = "ep1"))
        queue.enqueue(update("pod1", currentTime = 9.0, lastUpdate = 2_000, episodeId = "ep2"))
        assertEquals(2, queue.snapshot().size)
        assertEquals(5.0, queue.pendingPosition("pod1", "ep1")!!, 1e-9)
        assertEquals(9.0, queue.pendingPosition("pod1", "ep2")!!, 1e-9)
        assertNull(queue.pendingPosition("pod1"))
    }

    // ---- pendingPosition ---------------------------------------------------

    @Test
    fun `pendingPosition is null for an unknown item`() {
        val queue = ABSProgressSyncQueue(queueFile())
        assertNull(queue.pendingPosition("nope"))
    }

    // ---- flush -------------------------------------------------------------

    @Test
    fun `flush sends the batch and clears the queue on success`() = runTest {
        val queue = ABSProgressSyncQueue(queueFile())
        queue.enqueue(update("book1", currentTime = 10.0, lastUpdate = 1_000))
        queue.enqueue(update("book2", currentTime = 20.0, lastUpdate = 1_000))
        val api = mockk<AudiobookshelfApi>()
        coEvery { api.batchUpdateProgress(any()) } returns Unit

        assertTrue(queue.flush(api))
        assertTrue(queue.isEmpty)
        assertFalse(queueFile().exists()) // drained queue deletes its file
        coVerify(exactly = 1) { api.batchUpdateProgress(match { it.size == 2 }) }
    }

    @Test
    fun `flush keeps everything on failure`() = runTest {
        val queue = ABSProgressSyncQueue(queueFile())
        queue.enqueue(update("book1", currentTime = 10.0, lastUpdate = 1_000))
        val api = mockk<AudiobookshelfApi>()
        coEvery { api.batchUpdateProgress(any()) } throws AudiobookshelfApiException.ServerError(500)

        assertFalse(queue.flush(api))
        assertEquals(10.0, queue.pendingPosition("book1")!!, 1e-9)
    }

    @Test
    fun `an entry enqueued during an in-flight flush survives the clear`() = runTest {
        val queue = ABSProgressSyncQueue(queueFile())
        queue.enqueue(update("book1", currentTime = 10.0, lastUpdate = 1_000))
        val api = mockk<AudiobookshelfApi>()
        coEvery { api.batchUpdateProgress(any()) } answers {
            // A newer position arrives while the batch request is in flight.
            queue.enqueue(update("book1", currentTime = 30.0, lastUpdate = 2_000))
        }

        assertTrue(queue.flush(api))
        // Only the SENT snapshot was cleared — the in-flight enqueue survives.
        assertEquals(30.0, queue.pendingPosition("book1")!!, 1e-9)
        assertEquals(1, queue.snapshot().size)
    }

    @Test
    fun `flush of an empty queue is a successful no-op`() = runTest {
        val queue = ABSProgressSyncQueue(queueFile())
        val api = mockk<AudiobookshelfApi>()
        assertTrue(queue.flush(api))
        coVerify(exactly = 0) { api.batchUpdateProgress(any()) }
    }

    // ---- disk round-trip ---------------------------------------------------

    @Test
    fun `queue contents survive a restart via the disk file`() {
        val file = queueFile()
        val first = ABSProgressSyncQueue(file)
        first.enqueue(update("book1", currentTime = 42.0, lastUpdate = 1_000))
        first.enqueue(update("pod1", currentTime = 7.0, lastUpdate = 1_500, episodeId = "ep1"))

        val reloaded = ABSProgressSyncQueue(file)
        assertEquals(42.0, reloaded.pendingPosition("book1")!!, 1e-9)
        assertEquals(7.0, reloaded.pendingPosition("pod1", "ep1")!!, 1e-9)
    }

    @Test
    fun `persist writes via a temp file and leaves no temp behind`() {
        val file = queueFile()
        val queue = ABSProgressSyncQueue(file)
        queue.enqueue(update("book1", currentTime = 42.0, lastUpdate = 1_000))
        // Atomic write (review M3): the real file holds valid JSON and the
        // sibling temp file never lingers after a successful persist.
        assertTrue(file.exists())
        assertFalse(File(tmp.root, "abs_progress_queue.json.tmp").exists())
        assertEquals(42.0, ABSProgressSyncQueue(file).pendingPosition("book1")!!, 1e-9)
    }

    @Test
    fun `a corrupt queue file degrades to an empty queue`() {
        val file = queueFile()
        file.writeText("not json at all {]")
        val queue = ABSProgressSyncQueue(file)
        assertTrue(queue.isEmpty)
    }
}
