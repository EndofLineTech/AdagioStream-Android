package com.adagiostream.android.service.library.db

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * AudiobookDownloadDao CRUD coverage on an in-memory Room database
 * (beads_adagio-59p.1.6). Robolectric provides the SQLite engine on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class AudiobookDownloadDaoTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var dao: AudiobookDownloadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.audiobookDownloadDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(id: String = "book1") = AudiobookDownloadEntity(
        id = id,
        accountId = "acct1",
        title = "My Book",
        author = "Author",
        coverPath = "/dl/cover.webp",
        duration = 400.0,
        status = DownloadStatus.DOWNLOADING,
        filesJson = """[{"index":0,"ino":"42","startOffset":0.0,"duration":100.0}]""",
        chaptersJson = """[{"id":0,"title":"Ch 0","start":0.0,"end":90.0}]""",
        currentTime = 12.5,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `upsert then getById round-trips the manifest row`() = runTest {
        dao.upsert(row())
        val read = dao.getById("book1")!!
        assertEquals(row(), read)
    }

    @Test
    fun `upsert replaces on the same id`() = runTest {
        dao.upsert(row())
        dao.upsert(row().copy(status = DownloadStatus.COMPLETED, updatedAt = 2_000L))
        assertEquals(DownloadStatus.COMPLETED, dao.getById("book1")!!.status)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `updateCurrentTime persists the offline resume cache`() = runTest {
        dao.upsert(row())
        dao.updateCurrentTime("book1", 250.0, 3_000L)
        val read = dao.getById("book1")!!
        assertEquals(250.0, read.currentTime, 1e-9)
        assertEquals(3_000L, read.updatedAt)
    }

    @Test
    fun `deleteById removes the row`() = runTest {
        dao.upsert(row())
        dao.deleteById("book1")
        assertNull(dao.getById("book1"))
    }
}
