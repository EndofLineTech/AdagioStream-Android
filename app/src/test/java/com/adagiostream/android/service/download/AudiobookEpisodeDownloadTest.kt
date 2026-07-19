package com.adagiostream.android.service.download

import android.content.Context
import androidx.room.Room
import com.adagiostream.android.service.library.db.AudiobookDownloadDao
import com.adagiostream.android.service.library.db.AudiobookDownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.library.db.NavidromeCacheDatabase
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Episode-download record keying + offline lookup (beads_adagio-59p.2.3).
 *
 * Reuses the audiobook download stack: one episode = one manifest row under a
 * composite id, so episodes never collide with the show's book id or with each
 * other. Real in-memory Room DAO + in-memory file store; WorkManager is never
 * touched (the offline/save methods don't reach it), so a mock Context suffices.
 */
@RunWith(RobolectricTestRunner::class)
class AudiobookEpisodeDownloadTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var dao: AudiobookDownloadDao
    private lateinit var files: FakeFileStore
    private lateinit var manager: AudiobookDownloadManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.audiobookDownloadDao()
        files = FakeFileStore()
        manager = AudiobookDownloadManager(mockk<Context>(), dao, files)
    }

    @After
    fun tearDown() = db.close()

    private class FakeFileStore : DownloadFileStore {
        val data = HashMap<String, ByteArray>()
        override fun fileFor(trackId: String, suffix: String?): String =
            "/dl/$trackId" + (suffix?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
        override fun sizeOf(path: String): Long = (data[path]?.size ?: 0).toLong()
        override fun exists(path: String): Boolean = data.containsKey(path)
        override fun append(path: String, bytes: ByteArray, len: Int) {
            data[path] = (data[path] ?: ByteArray(0)) + bytes.copyOf(len)
        }
        override fun truncate(path: String) { data[path] = ByteArray(0) }
        override fun delete(path: String): Boolean = data.remove(path) != null
    }

    /** Inserts a COMPLETE single-file episode manifest with its file on disk. */
    private suspend fun seedEpisode(showId: String, episodeId: String, currentTime: Double = 0.0) {
        val recordId = AudiobookDownloadManager.episodeRecordId(showId, episodeId)
        val path = "/dl/${recordId}.m4b"
        files.append(path, byteArrayOf(1, 2, 3), 3)
        dao.upsert(
            AudiobookDownloadEntity(
                id = recordId,
                accountId = "acct",
                title = "Episode $episodeId",
                author = "The Show",
                coverPath = null,
                duration = 1800.0,
                status = DownloadStatus.COMPLETED,
                filesJson = encodeManifestFiles(
                    listOf(AudiobookDownloadFile(0, "77", 0.0, 1800.0, "m4b", path)),
                ),
                chaptersJson = "[]",
                currentTime = currentTime,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    @Test
    fun `episode record id is collision-free vs book and sibling episodes`() {
        val bookId = "show1" // a book's record id is the bare library-item id
        val ep1 = AudiobookDownloadManager.episodeRecordId("show1", "ep1")
        val ep2 = AudiobookDownloadManager.episodeRecordId("show1", "ep2")
        val otherShow = AudiobookDownloadManager.episodeRecordId("show2", "ep1")

        assertNotEquals(bookId, ep1)
        assertNotEquals(ep1, ep2)
        assertNotEquals(ep1, otherShow)
        assertTrue(ep1.startsWith("episode:"))
    }

    @Test
    fun `completeEpisode reconstructs the offline timeline under the show id`() = runTest {
        seedEpisode("show1", "ep1", currentTime = 300.0)

        val offline = manager.completeEpisode("show1", "ep1")!!
        // Plays under the SHOW library-item id, NOT the composite record id.
        assertEquals("show1", offline.libraryItemId)
        assertEquals(300.0, offline.currentTime, 1e-9)
        assertEquals(1, offline.timeline.files.size)
        assertTrue(offline.timeline.files.single().contentUrl.startsWith("file:"))
        assertEquals(1800.0, offline.timeline.totalDuration, 1e-9)
    }

    @Test
    fun `episode and book lookups never resolve each other`() = runTest {
        seedEpisode("show1", "ep1")

        // The show id as a BOOK has no row → book lookup misses the episode row.
        assertNull(manager.completeBook("show1"))
        // A sibling episode of the same show has no row.
        assertNull(manager.completeEpisode("show1", "ep2"))
        // The episode itself resolves.
        assertEquals("show1", manager.completeEpisode("show1", "ep1")!!.libraryItemId)
    }

    @Test
    fun `savePosition routes episodes to the composite key and books to the bare id`() = runTest {
        seedEpisode("show1", "ep1")
        dao.upsert(
            AudiobookDownloadEntity(
                id = "book1", accountId = "acct", title = "Book", status = DownloadStatus.COMPLETED,
                createdAt = 1, updatedAt = 1,
            ),
        )

        manager.savePosition("show1", "ep1", 450.0)
        manager.savePosition("book1", null, 90.0)

        val epRow = dao.getById(AudiobookDownloadManager.episodeRecordId("show1", "ep1"))!!
        assertEquals(450.0, epRow.currentTime, 1e-9)
        assertEquals(90.0, dao.getById("book1")!!.currentTime, 1e-9)
    }

    @Test
    fun `deleting an episode removes its files and row without touching a same-show book`() = runTest {
        seedEpisode("show1", "ep1")
        val recordId = AudiobookDownloadManager.episodeRecordId("show1", "ep1")
        val epPath = "/dl/${recordId}.m4b"
        dao.upsert(
            AudiobookDownloadEntity(
                id = "show1", accountId = "acct", title = "A Book", status = DownloadStatus.COMPLETED,
                createdAt = 1, updatedAt = 1,
            ),
        )

        // The removal logic behind AudiobookDownloadManager.deleteEpisode (the
        // WorkManager cancel is device-only, like the book delete path).
        removeAudiobookDownload(recordId, dao, files)

        assertNull(dao.getById(recordId))
        assertTrue(!files.exists(epPath))
        // The same-show book row is untouched.
        assertEquals("A Book", dao.getById("show1")!!.title)
    }
}
