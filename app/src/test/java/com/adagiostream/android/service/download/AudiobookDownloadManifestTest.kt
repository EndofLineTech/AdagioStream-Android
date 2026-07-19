package com.adagiostream.android.service.download

import com.adagiostream.android.service.audiobookshelf.AbsChapter
import com.adagiostream.android.service.library.db.AudiobookDownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manifest codec + timeline reconstruction (beads_adagio-59p.1.6): the offline
 * timeline rebuilt from a persisted manifest must carry the exact global
 * offsets captured from the streaming session at download time. Pure JVM.
 */
class AudiobookDownloadManifestTest {

    private val files = listOf(
        AudiobookDownloadFile(index = 0, ino = "10", startOffset = 0.0, duration = 100.0, ext = "m4b", localPath = "/dl/f0.m4b"),
        AudiobookDownloadFile(index = 1, ino = "11", startOffset = 100.0, duration = 150.0, ext = "m4b", localPath = "/dl/f1.m4b"),
        AudiobookDownloadFile(index = 2, ino = "12", startOffset = 250.0, duration = 150.0, ext = "m4b", localPath = "/dl/f2.m4b"),
    )
    private val chapters = listOf(
        AbsChapter(0, "Chapter 0", 0.0, 90.0),
        AbsChapter(1, "Chapter 1", 90.0, 260.0),
    )

    private fun entity(
        filesJson: String = encodeManifestFiles(files),
        chaptersJson: String = encodeManifestChapters(chapters),
    ) = AudiobookDownloadEntity(
        id = "book1",
        accountId = "acct1",
        title = "My Book",
        status = DownloadStatus.COMPLETED,
        filesJson = filesJson,
        chaptersJson = chaptersJson,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `files and chapters round-trip through the JSON columns`() {
        val e = entity()
        assertEquals(files, e.manifestFiles())
        assertEquals(chapters, e.manifestChapters())
    }

    @Test
    fun `corrupt JSON degrades to empty, never throws`() {
        val e = entity(filesJson = "{not json", chaptersJson = "42")
        assertTrue(e.manifestFiles().isEmpty())
        assertTrue(e.manifestChapters().isEmpty())
    }

    @Test
    fun `offline timeline preserves the original global offsets and chapters`() {
        val tl = entity().offlineTimeline()
        assertEquals(400.0, tl.totalDuration, 1e-9)
        assertEquals(listOf(0.0, 100.0, 250.0), tl.files.map { it.startOffset })
        assertEquals(listOf(100.0, 150.0, 150.0), tl.files.map { it.duration })
        assertEquals(chapters, tl.chapters)
        // Content URLs are ready-to-play file:// URIs of the local paths.
        assertTrue(tl.files.all { it.contentUrl.startsWith("file:") })
        assertTrue(tl.files[1].contentUrl.endsWith("/dl/f1.m4b"))
        // Global↔file mapping matches the streaming timeline semantics.
        val loc = tl.locate(120.0)!!
        assertEquals(1, loc.file.index)
        assertEquals(20.0, loc.fileOffset, 1e-9)
    }

    @Test
    fun `files without a localPath are skipped in the offline timeline`() {
        val partial = files.map { if (it.index == 2) it.copy(localPath = null) else it }
        val tl = entity(filesJson = encodeManifestFiles(partial)).offlineTimeline()
        assertEquals(2, tl.files.size)
        assertEquals(250.0, tl.totalDuration, 1e-9)
    }

    @Test
    fun `allFilesPresent requires every file on disk with bytes`() {
        val store = object : DownloadFileStore {
            val present = mutableSetOf("/dl/f0.m4b", "/dl/f1.m4b", "/dl/f2.m4b")
            override fun fileFor(trackId: String, suffix: String?): String = "/dl/$trackId"
            override fun sizeOf(path: String): Long = if (path in present) 10L else 0L
            override fun exists(path: String): Boolean = path in present
            override fun append(path: String, bytes: ByteArray, len: Int) = Unit
            override fun truncate(path: String) = Unit
            override fun delete(path: String): Boolean = present.remove(path)
        }
        assertTrue(entity().allFilesPresent(store))

        store.present.remove("/dl/f1.m4b")
        assertFalse(entity().allFilesPresent(store))

        // A manifest with a pending (localPath == null) file is never complete.
        val pending = files.map { it.copy(localPath = null) }
        assertFalse(entity(filesJson = encodeManifestFiles(pending)).allFilesPresent(store))

        // An empty manifest is never complete.
        assertFalse(entity(filesJson = "[]").allFilesPresent(store))
    }
}
