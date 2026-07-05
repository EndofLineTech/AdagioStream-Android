package com.adagiostream.android.service.library.db

import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DownloadDao CRUD + status-transition coverage on an in-memory Room database
 * (baw.6.1 TDD). Robolectric provides the SQLite engine on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadDaoTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var dao: DownloadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.downloadDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        id: String,
        status: String = DownloadStatus.QUEUED,
        resumeOffset: Long = 0,
        bytesTotal: Long = 0,
        localPath: String? = null,
        now: Long = 1_000L,
    ) = DownloadEntity(
        id = id,
        status = status,
        localPath = localPath,
        resumeOffset = resumeOffset,
        bytesTotal = bytesTotal,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `upsert then getById round-trips the row`() = runTest {
        dao.upsert(row("t1", bytesTotal = 4242))
        val read = dao.getById("t1")
        assertEquals("t1", read?.id)
        assertEquals(DownloadStatus.QUEUED, read?.status)
        assertEquals(4242L, read?.bytesTotal)
    }

    @Test
    fun `upsert with same id replaces the existing row`() = runTest {
        dao.upsert(row("t1", status = DownloadStatus.QUEUED))
        dao.upsert(row("t1", status = DownloadStatus.COMPLETED, localPath = "/x/t1.mp3"))
        assertEquals(1, dao.getAll().size)
        assertEquals(DownloadStatus.COMPLETED, dao.getById("t1")?.status)
        assertEquals("/x/t1.mp3", dao.getById("t1")?.localPath)
    }

    @Test
    fun `updateStatus transitions queued to downloading`() = runTest {
        dao.upsert(row("t1", status = DownloadStatus.QUEUED))
        dao.updateStatus("t1", DownloadStatus.DOWNLOADING, updatedAt = 2_000L)
        val read = dao.getById("t1")
        assertEquals(DownloadStatus.DOWNLOADING, read?.status)
        assertEquals(2_000L, read?.updatedAt)
    }

    @Test
    fun `updateProgress persists resume offset and total for a failed download`() = runTest {
        dao.upsert(row("t1", status = DownloadStatus.DOWNLOADING))
        dao.updateProgress(
            id = "t1",
            status = DownloadStatus.FAILED,
            resumeOffset = 512,
            bytesTotal = 2048,
            localPath = null,
            error = "boom",
            updatedAt = 3_000L,
        )
        val read = dao.getById("t1")!!
        assertEquals(DownloadStatus.FAILED, read.status)
        assertEquals(512L, read.resumeOffset)
        assertEquals(2048L, read.bytesTotal)
        assertEquals("boom", read.error)
    }

    @Test
    fun `getActive returns only queued downloading and paused`() = runTest {
        dao.upsert(row("a", status = DownloadStatus.QUEUED))
        dao.upsert(row("b", status = DownloadStatus.DOWNLOADING))
        dao.upsert(row("c", status = DownloadStatus.PAUSED))
        dao.upsert(row("d", status = DownloadStatus.COMPLETED))
        dao.upsert(row("e", status = DownloadStatus.FAILED))

        val active = dao.getActive().map { it.id }.toSet()
        assertEquals(setOf("a", "b", "c"), active)
    }

    @Test
    fun `deleteById removes only the targeted row`() = runTest {
        dao.upsert(row("a"))
        dao.upsert(row("b"))
        dao.deleteById("a")
        assertNull(dao.getById("a"))
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `completedCount counts only completed rows`() = runTest {
        dao.upsert(row("a", status = DownloadStatus.COMPLETED))
        dao.upsert(row("b", status = DownloadStatus.COMPLETED))
        dao.upsert(row("c", status = DownloadStatus.QUEUED))
        assertEquals(2, dao.completedCount())
    }

    @Test
    fun `observeById emits updates as the row transitions`() = runTest {
        dao.upsert(row("t1", status = DownloadStatus.QUEUED))
        dao.observeById("t1").test {
            assertEquals(DownloadStatus.QUEUED, awaitItem()?.status)
            dao.updateStatus("t1", DownloadStatus.COMPLETED, updatedAt = 9_000L)
            assertEquals(DownloadStatus.COMPLETED, awaitItem()?.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeCompleted lists completed downloads reactively`() = runTest {
        dao.upsert(row("a", status = DownloadStatus.QUEUED))
        dao.upsert(row("b", status = DownloadStatus.COMPLETED))
        val emitted = dao.observeCompleted().first()
        assertEquals(listOf("b"), emitted.map { it.id })
    }

    @Test
    fun `deleteAll clears the table`() = runTest {
        dao.upsert(row("a"))
        dao.upsert(row("b"))
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }
}
