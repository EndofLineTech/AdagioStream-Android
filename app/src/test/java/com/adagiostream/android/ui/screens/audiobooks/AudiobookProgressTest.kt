package com.adagiostream.android.ui.screens.audiobooks

import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AbsMediaProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure helper tests (beads_adagio-59p.1.4): Continue Listening filtering
 * (episode exclusion, started-not-finished, 0.99 boundary) and progress
 * badge formatting.
 */
class AudiobookProgressTest {

    private fun book(
        id: String,
        progress: Double? = null,
        isFinished: Boolean? = null,
        episode: Boolean = false,
    ): AbsLibraryItem = AbsLibraryItem(
        id = id,
        userMediaProgress = if (progress != null || isFinished != null) {
            AbsMediaProgress(progress = progress, isFinished = isFinished)
        } else {
            null
        },
        recentEpisode = if (episode) AbsEpisode(id = "ep-$id") else null,
    )

    // -------------------------------------------------------------------------
    // continueListeningBooks
    // -------------------------------------------------------------------------

    @Test
    fun `keeps started unfinished books in server order`() {
        val items = listOf(
            book("a", progress = 0.5),
            book("b", progress = 0.01),
        )
        assertEquals(listOf("a", "b"), continueListeningBooks(items).map { it.id })
    }

    @Test
    fun `excludes podcast episodes by recentEpisode discriminator`() {
        val items = listOf(
            book("show", progress = 0.5, episode = true),
            book("book", progress = 0.5),
        )
        assertEquals(listOf("book"), continueListeningBooks(items).map { it.id })
    }

    @Test
    fun `excludes unstarted books`() {
        val items = listOf(
            book("no-progress-record"),
            book("zero", progress = 0.0),
            book("started", progress = 0.2),
        )
        assertEquals(listOf("started"), continueListeningBooks(items).map { it.id })
    }

    @Test
    fun `excludes books finished by flag even at low progress`() {
        val items = listOf(book("flagged", progress = 0.4, isFinished = true))
        assertEquals(emptyList<String>(), continueListeningBooks(items).map { it.id })
    }

    @Test
    fun `epsilon boundary - 0_99 is finished, just below is not`() {
        val items = listOf(
            book("at-threshold", progress = 0.99),
            book("above", progress = 0.995),
            book("below", progress = 0.989),
        )
        assertEquals(listOf("below"), continueListeningBooks(items).map { it.id })
    }

    // -------------------------------------------------------------------------
    // progressBadge
    // -------------------------------------------------------------------------

    @Test
    fun `badge is null for unstarted books`() {
        assertNull(progressBadge(book("a")))
        assertNull(progressBadge(book("b", progress = 0.0)))
    }

    @Test
    fun `badge shows percent listened for in-progress books`() {
        assertEquals("45% listened", progressBadge(book("a", progress = 0.45)))
        assertEquals("1% listened", progressBadge(book("b", progress = 0.011)))
    }

    @Test
    fun `badge shows Finished at the flag or the 0_99 threshold`() {
        assertEquals("Finished", progressBadge(book("a", progress = 0.4, isFinished = true)))
        assertEquals("Finished", progressBadge(book("b", progress = 0.99)))
        assertEquals("Finished", progressBadge(book("c", progress = 1.0)))
    }

    // -------------------------------------------------------------------------
    // formatBookDuration
    // -------------------------------------------------------------------------

    @Test
    fun `duration formats as hours+minutes or minutes only`() {
        assertEquals("3h 24m", formatBookDuration(3.0 * 3600 + 24 * 60))
        assertEquals("52m", formatBookDuration(52.0 * 60))
        assertEquals("0m", formatBookDuration(12.0))
    }
}
