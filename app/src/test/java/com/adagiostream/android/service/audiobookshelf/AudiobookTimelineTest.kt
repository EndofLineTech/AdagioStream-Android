package com.adagiostream.android.service.audiobookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Timeline math for multi-file audiobooks (beads_adagio-59p.1.5): global↔file
 * mapping with clamping, file chaining, and chapter navigation including the
 * ">3s into a chapter restarts it" previous rule. Pure — no clocks, no I/O.
 */
class AudiobookTimelineTest {

    private fun file(index: Int, startOffset: Double, duration: Double) = AbsAudioTrack(
        index = index,
        startOffset = startOffset,
        duration = duration,
        title = "file$index",
        contentUrl = "/hls/session/file$index.m4b",
    )

    private fun chapter(id: Int, start: Double, end: Double) =
        AbsChapter(id = id, title = "Chapter $id", start = start, end = end)

    // Three files: [0, 100), [100, 250), [250, 400)
    private val files = listOf(
        file(0, 0.0, 100.0),
        file(1, 100.0, 150.0),
        file(2, 250.0, 150.0),
    )

    // Chapters: [0, 90), [90, 260), [260, 400)
    private val chapters = listOf(
        chapter(0, 0.0, 90.0),
        chapter(1, 90.0, 260.0),
        chapter(2, 260.0, 400.0),
    )

    private val timeline = AudiobookTimeline(files, chapters)

    // ---- locate ------------------------------------------------------------

    @Test
    fun `locate resolves a mid-file position`() {
        val loc = timeline.locate(150.0)!!
        assertEquals(1, loc.file.index)
        assertEquals(50.0, loc.fileOffset, 1e-9)
    }

    @Test
    fun `locate at an exact file boundary lands at the start of the later file`() {
        val loc = timeline.locate(100.0)!!
        assertEquals(1, loc.file.index)
        assertEquals(0.0, loc.fileOffset, 1e-9)
    }

    @Test
    fun `locate at zero returns the first file at offset zero`() {
        val loc = timeline.locate(0.0)!!
        assertEquals(0, loc.file.index)
        assertEquals(0.0, loc.fileOffset, 1e-9)
    }

    @Test
    fun `locate clamps a negative position to the book start`() {
        val loc = timeline.locate(-37.0)!!
        assertEquals(0, loc.file.index)
        assertEquals(0.0, loc.fileOffset, 1e-9)
    }

    @Test
    fun `locate clamps past-the-end to the last file at its end`() {
        val loc = timeline.locate(9_999.0)!!
        assertEquals(2, loc.file.index)
        assertEquals(150.0, loc.fileOffset, 1e-9)
    }

    @Test
    fun `locate on an empty timeline returns null`() {
        assertNull(AudiobookTimeline(emptyList()).locate(10.0))
    }

    @Test
    fun `files are sorted by startOffset regardless of input order`() {
        val shuffled = AudiobookTimeline(files.reversed(), chapters)
        assertEquals(listOf(0, 1, 2), shuffled.files.map { it.index })
        assertEquals(400.0, shuffled.totalDuration, 1e-9)
    }

    // ---- globalTime round-trip --------------------------------------------

    @Test
    fun `globalTime is the inverse of locate`() {
        for (t in listOf(0.0, 42.0, 100.0, 249.9, 250.0, 399.0, 400.0)) {
            val loc = timeline.locate(t)!!
            assertEquals(t, timeline.globalTime(loc.file, loc.fileOffset), 1e-9)
        }
    }

    @Test
    fun `globalTime clamps a negative file offset to the file start`() {
        assertEquals(100.0, timeline.globalTime(files[1], -5.0), 1e-9)
    }

    // ---- fileAfter ---------------------------------------------------------

    @Test
    fun `fileAfter chains through the book and ends with null`() {
        assertEquals(1, timeline.fileAfter(files[0])!!.index)
        assertEquals(2, timeline.fileAfter(files[1])!!.index)
        assertNull(timeline.fileAfter(files[2]))
    }

    // ---- chapterAt ---------------------------------------------------------

    @Test
    fun `chapterAt maps positions to chapters with half-open intervals`() {
        assertEquals(0, timeline.chapterAt(0.0)!!.id)
        assertEquals(0, timeline.chapterAt(89.9)!!.id)
        assertEquals(1, timeline.chapterAt(90.0)!!.id)
        assertEquals(2, timeline.chapterAt(399.0)!!.id)
    }

    @Test
    fun `chapterAt at the book end falls back to the last chapter`() {
        assertEquals(2, timeline.chapterAt(400.0)!!.id)
    }

    @Test
    fun `chapterAt with no chapters returns null`() {
        assertNull(AudiobookTimeline(files).chapterAt(50.0))
    }

    // ---- nextChapterStart --------------------------------------------------

    @Test
    fun `nextChapterStart returns the following chapter start`() {
        assertEquals(90.0, timeline.nextChapterStart(10.0)!!, 1e-9)
        assertEquals(260.0, timeline.nextChapterStart(90.0)!!, 1e-9)
    }

    @Test
    fun `nextChapterStart within the boundary tolerance skips the adjacent start`() {
        // 89.8 + 0.5 tolerance passes chapter 1's start (90.0) → next is chapter 2.
        assertEquals(260.0, timeline.nextChapterStart(89.8)!!, 1e-9)
    }

    @Test
    fun `nextChapterStart in the last chapter returns null`() {
        assertNull(timeline.nextChapterStart(300.0))
    }

    // ---- previousChapterStart (the 3s rule) -------------------------------

    @Test
    fun `previous more than 3s into a chapter restarts the current chapter`() {
        assertEquals(90.0, timeline.previousChapterStart(95.0)!!, 1e-9)
    }

    @Test
    fun `previous within 3s of a chapter start jumps to the preceding chapter`() {
        assertEquals(0.0, timeline.previousChapterStart(92.0)!!, 1e-9)
    }

    @Test
    fun `previous exactly 3s in still goes to the preceding chapter`() {
        // Rule is strictly greater than 3s for restart.
        assertEquals(0.0, timeline.previousChapterStart(93.0)!!, 1e-9)
    }

    @Test
    fun `previous within 3s of the first chapter stays at its start`() {
        assertEquals(0.0, timeline.previousChapterStart(2.0)!!, 1e-9)
    }

    @Test
    fun `previous with no chapters returns null`() {
        assertNull(AudiobookTimeline(files).previousChapterStart(50.0))
    }
}
