package com.adagiostream.android.service.download

import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.library.db.NavidromeCacheDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Download state-machine + byte-range resume coverage (baw.6.1 TDD).
 *
 * Uses a real in-memory [DownloadDao] (Room/Robolectric), an in-memory file store,
 * and a scriptable fake [ByteRangeDownloader] — so the queued→downloading→completed,
 * →failed, and retry-resumes-from-offset transitions are verified without a network
 * or real WorkManager (those are device-only).
 */
@RunWith(RobolectricTestRunner::class)
class TrackDownloaderTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var dao: DownloadDao
    private lateinit var files: FakeFileStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.downloadDao()
        files = FakeFileStore()
    }

    @After
    fun tearDown() = db.close()

    // ---- in-memory fakes -------------------------------------------------

    /** In-memory file store; `sizeOf` is the resume source of truth. */
    private class FakeFileStore : DownloadFileStore {
        val data = HashMap<String, ByteArray>()
        override fun fileFor(trackId: String, suffix: String?): String = "/dl/$trackId"
        override fun sizeOf(path: String): Long = (data[path]?.size ?: 0).toLong()
        override fun exists(path: String): Boolean = data.containsKey(path)
        override fun append(path: String, bytes: ByteArray, len: Int) {
            val existing = data[path] ?: ByteArray(0)
            data[path] = existing + bytes.copyOf(len)
        }
        override fun truncate(path: String) { data[path] = ByteArray(0) }
        override fun delete(path: String): Boolean = data.remove(path) != null
    }

    /** A range response over a byte array; can fail after N bytes. */
    private class FakeResponse(
        full: ByteArray,
        offset: Long,
        override val totalBytes: Long?,
        override val partial: Boolean,
        private val failAfter: Int = -1,
    ) : RangeResponse {
        private val stream = ByteArrayInputStream(full.copyOfRange(offset.toInt(), full.size))
        private var served = 0
        override fun read(buffer: ByteArray): Int {
            if (failAfter in 0..served) throw IOException("network dropped")
            val n = stream.read(buffer)
            if (n > 0) served += n
            return n
        }
        override fun close() {}
    }

    /** Records each open(url, offset); scripts per-attempt responses. */
    private class FakeDownloader(
        private val full: ByteArray,
        private val script: (attempt: Int, offset: Long) -> RangeResponse,
    ) : ByteRangeDownloader {
        val offsets = mutableListOf<Long>()
        override suspend fun open(url: String, offset: Long): RangeResponse {
            val attempt = offsets.size
            offsets.add(offset)
            return script(attempt, offset)
        }
    }

    private fun downloader(
        full: ByteArray,
        onBytes: (suspend (String, Long, Long?) -> Unit)? = null,
        source: FakeDownloader,
    ) = TrackDownloader(dao, source, files, now = { clock }, onBytes = onBytes)

    // ---- tests -----------------------------------------------------------

    @Test
    fun `download streams to completion and marks completed`() = runTest {
        val payload = ByteArray(2000) { it.toByte() }
        val source = FakeDownloader(payload) { _, offset ->
            FakeResponse(payload, offset, totalBytes = 2000, partial = offset > 0)
        }
        val outcome = downloader(payload, source = source).download("t1", "http://x/download?id=t1")

        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals(2000L, (outcome as DownloadOutcome.Completed).bytesWritten)
        val row = dao.getById("t1")!!
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertEquals("/dl/t1", row.localPath)
        assertEquals(2000L, row.bytesTotal)
        assertEquals(2000, files.data["/dl/t1"]!!.size)
    }

    @Test
    fun `failure mid-stream marks failed and stores resume offset from disk`() = runTest {
        // Serves 4 bytes, then the stream throws — failed state must persist the
        // bytes-on-disk as the resume offset.
        val small = ByteArray(10) { 1 }
        val source2 = FakeDownloader(small) { _, offset ->
            object : RangeResponse {
                override val totalBytes = 10L
                override val partial = offset > 0
                private var calls = 0
                override fun read(buffer: ByteArray): Int {
                    calls++
                    if (calls == 1) { // serve 4 bytes
                        System.arraycopy(small, 0, buffer, 0, 4)
                        return 4
                    }
                    throw IOException("dropped")
                }
                override fun close() {}
            }
        }
        val outcome = TrackDownloader(dao, source2, files, now = { clock })
            .download("t1", "http://x")

        assertTrue(outcome is DownloadOutcome.Failed)
        val row = dao.getById("t1")!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(4L, row.resumeOffset)
        assertEquals(4, files.data["/dl/t1"]!!.size)
    }

    @Test
    fun `retry resumes from the stored offset and sends a range request`() = runTest {
        val payload = ByteArray(10) { (it + 1).toByte() }

        // Attempt 0: serve 4 bytes then fail. Attempt 1: serve the rest from offset 4.
        val source = FakeDownloader(payload) { attempt, offset ->
            if (attempt == 0) {
                object : RangeResponse {
                    override val totalBytes = 10L
                    override val partial = false
                    private var calls = 0
                    override fun read(buffer: ByteArray): Int {
                        calls++
                        if (calls == 1) { System.arraycopy(payload, 0, buffer, 0, 4); return 4 }
                        throw IOException("dropped")
                    }
                    override fun close() {}
                }
            } else {
                FakeResponse(payload, offset, totalBytes = 10, partial = true)
            }
        }

        val downloader = TrackDownloader(dao, source, files, now = { clock })
        val first = downloader.download("t1", "http://x")
        assertTrue(first is DownloadOutcome.Failed)

        val second = downloader.download("t1", "http://x")
        assertTrue(second is DownloadOutcome.Completed)

        // Second open was at offset 4 (the bytes already on disk) — byte-range resume.
        assertEquals(listOf(0L, 4L), source.offsets)
        assertEquals(10, files.data["/dl/t1"]!!.size)
        assertEquals(DownloadStatus.COMPLETED, dao.getById("t1")!!.status)
    }

    @Test
    fun `server ignoring range restarts from zero`() = runTest {
        val payload = ByteArray(8) { (it + 1).toByte() }
        // Pre-seed a stale partial file of 3 bytes.
        files.data["/dl/t1"] = byteArrayOf(99, 99, 99)

        // Server returns full body (partial=false) despite the offset.
        val source = FakeDownloader(payload) { _, offset ->
            FakeResponse(payload, 0, totalBytes = 8, partial = false)
        }
        val outcome = TrackDownloader(dao, source, files, now = { clock }).download("t1", "http://x")

        assertTrue(outcome is DownloadOutcome.Completed)
        // Restarted: file is exactly the fresh 8-byte payload, not 3 stale + 8.
        assertEquals(8, files.data["/dl/t1"]!!.size)
        assertTrue(files.data["/dl/t1"]!!.contentEquals(payload))
    }

    @Test
    fun `already-completed download short-circuits without opening the network`() = runTest {
        val payload = ByteArray(5) { 7 }
        files.data["/dl/t1"] = payload
        dao.upsert(
            com.adagiostream.android.service.library.db.DownloadEntity(
                id = "t1", status = DownloadStatus.COMPLETED, localPath = "/dl/t1",
                resumeOffset = 5, bytesTotal = 5, createdAt = 1, updatedAt = 1,
            ),
        )
        val source = FakeDownloader(payload) { _, _ -> error("should not open") }
        val outcome = TrackDownloader(dao, source, files, now = { clock }).download("t1", "http://x")

        assertTrue(outcome is DownloadOutcome.AlreadyComplete)
        assertTrue(source.offsets.isEmpty())
    }
}
