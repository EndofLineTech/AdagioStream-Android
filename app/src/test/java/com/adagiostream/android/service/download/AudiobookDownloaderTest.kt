package com.adagiostream.android.service.download

import androidx.room.Room
import com.adagiostream.android.service.audiobookshelf.AbsChapter
import com.adagiostream.android.service.library.db.AudiobookDownloadDao
import com.adagiostream.android.service.library.db.NavidromeCacheDatabase
import com.adagiostream.android.service.library.db.DownloadStatus
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Audiobook download lifecycle coverage (beads_adagio-59p.1.6 TDD): manifest
 * creation from the plan, per-file download with file-granular resume, failure
 * handling, and deletion. Real in-memory Room DAO (Robolectric), in-memory
 * file store, scripted plan/file seams — no network, no WorkManager (matches
 * [TrackDownloaderTest]'s approach).
 */
@RunWith(RobolectricTestRunner::class)
class AudiobookDownloaderTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var dao: AudiobookDownloadDao
    private lateinit var files: FakeFileStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.audiobookDownloadDao()
        files = FakeFileStore()
    }

    @After
    fun tearDown() = db.close()

    /** In-memory file store keyed by path. */
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

    private val plan = AudiobookDownloader.Plan(
        title = "My Book",
        author = "Author",
        duration = 400.0,
        files = listOf(
            AudiobookDownloadFile(index = 0, ino = "10", startOffset = 0.0, duration = 100.0, ext = "m4b"),
            AudiobookDownloadFile(index = 1, ino = "11", startOffset = 100.0, duration = 300.0, ext = "m4b"),
        ),
        chapters = listOf(AbsChapter(0, "Ch 0", 0.0, 400.0)),
    )

    private var planFetches = 0
    private val fetchedInos = mutableListOf<String>()

    private fun downloader(
        failIno: String? = null,
        withCover: Boolean = false,
    ) = AudiobookDownloader(
        dao = dao,
        files = files,
        fetchPlan = { planFetches++; plan },
        fetchFile = { file, dest ->
            fetchedInos += file.ino
            if (file.ino == failIno) throw IOException("network dropped")
            files.append(dest, ByteArray(64) { 1 }, 64)
        },
        fetchCover = if (withCover) {
            { dest -> files.append(dest, ByteArray(8) { 2 }, 8) }
        } else {
            null
        },
        now = { clock },
    )

    @Test
    fun `full download builds the manifest, fetches all files, marks completed`() = runTest {
        val outcome = downloader(withCover = true).download("book1", "acct1")

        assertTrue(outcome is AudiobookDownloader.Outcome.Complete)
        assertEquals(1, planFetches)
        assertEquals(listOf("10", "11"), fetchedInos)

        val row = dao.getById("book1")!!
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertEquals("My Book", row.title)
        assertEquals("Author", row.author)
        assertEquals("acct1", row.accountId)
        assertNull(row.error)
        assertNotNull(row.coverPath)
        assertTrue(files.exists(row.coverPath!!))
        assertTrue(row.allFilesPresent(files))
        // Global offsets survived verbatim into the manifest.
        assertEquals(listOf(0.0, 100.0), row.manifestFiles().map { it.startOffset })
        assertEquals(plan.chapters, row.manifestChapters())
    }

    @Test
    fun `re-enqueue skips files already present (file-granular resume)`() = runTest {
        // First attempt fails on the second file — file 0 landed, file 1 did not.
        val first = downloader(failIno = "11").download("book1", "acct1")
        assertTrue(first is AudiobookDownloader.Outcome.Failed)
        val failedRow = dao.getById("book1")!!
        assertEquals(DownloadStatus.FAILED, failedRow.status)
        assertEquals("network dropped", failedRow.error)
        assertNotNull(failedRow.manifestFiles()[0].localPath) // file 0 kept
        assertNull(failedRow.manifestFiles()[1].localPath)
        assertFalse(failedRow.allFilesPresent(files))

        // Retry: plan NOT re-fetched, only the missing file downloaded.
        fetchedInos.clear()
        val second = downloader().download("book1", "acct1")
        assertTrue(second is AudiobookDownloader.Outcome.Complete)
        assertEquals(1, planFetches) // manifest reuse — one plan fetch total
        assertEquals(listOf("11"), fetchedInos)
        assertTrue(dao.getById("book1")!!.allFilesPresent(files))
    }

    @Test
    fun `a completed book with files on disk is a no-network no-op`() = runTest {
        downloader().download("book1", "acct1")
        fetchedInos.clear()

        val outcome = downloader().download("book1", "acct1")
        assertTrue(outcome is AudiobookDownloader.Outcome.Complete)
        assertEquals(1, planFetches)
        assertTrue(fetchedInos.isEmpty())
    }

    @Test
    fun `a failed partial file is deleted so retry re-fetches it whole`() = runTest {
        val partialWriter = AudiobookDownloader(
            dao = dao,
            files = files,
            fetchPlan = { plan },
            fetchFile = { file, dest ->
                files.append(dest, ByteArray(10) { 3 }, 10) // some bytes land…
                if (file.ino == "11") throw IOException("mid-file drop") // …then the link dies
            },
            now = { clock },
        )
        assertTrue(partialWriter.download("book1", "acct1") is AudiobookDownloader.Outcome.Failed)
        // No half-written file left for the present-check to trust.
        assertTrue(files.data.keys.none { it.contains("_11") })
    }

    @Test
    fun `plan failure marks the row failed`() = runTest {
        val d = AudiobookDownloader(
            dao = dao,
            files = files,
            fetchPlan = { throw IOException("server unreachable") },
            fetchFile = { _, _ -> },
            now = { clock },
        )
        assertTrue(d.download("book1", "acct1") is AudiobookDownloader.Outcome.Failed)
        val row = dao.getById("book1")!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals("server unreachable", row.error)
    }

    @Test
    fun `removeAudiobookDownload deletes files, cover and the manifest row`() = runTest {
        downloader(withCover = true).download("book1", "acct1")
        assertTrue(files.data.isNotEmpty())

        removeAudiobookDownload("book1", dao, files)
        assertNull(dao.getById("book1"))
        assertTrue(files.data.isEmpty())
    }
}
