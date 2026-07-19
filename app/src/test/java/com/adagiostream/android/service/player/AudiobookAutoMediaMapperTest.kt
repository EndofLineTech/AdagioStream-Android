package com.adagiostream.android.service.player

import android.net.Uri
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AbsMedia
import com.adagiostream.android.service.audiobookshelf.AbsMediaProgress
import com.adagiostream.android.service.audiobookshelf.AbsMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [AudiobookAutoMediaMapper] (beads_adagio-59p.1.7).
 *
 * Mirrors [MusicAutoMediaMapperTest]'s goals:
 *
 * 1. **Audiobook browse path** — node IDs round-trip, the library-picker-vs-
 *    flat-list decision matches the iOS CarPlay shape, and book rows carry the
 *    author + progress-hint subtitle.
 *
 * 2. **Routing non-regression guard** — audiobook IDs are never captured by
 *    the IPTV or music sub-trees, and vice versa.
 */
@RunWith(RobolectricTestRunner::class)
class AudiobookAutoMediaMapperTest {

    private fun library(id: String, name: String = "Library $id") =
        AbsLibrary(id = id, name = name, mediaType = "book")

    private fun book(
        id: String,
        title: String? = "Book $id",
        author: String? = "Author $id",
        progress: Double? = null,
        isFinished: Boolean? = null,
    ) = AbsLibraryItem(
        id = id,
        media = AbsMedia(metadata = AbsMetadata(title = title, authorName = author)),
        userMediaProgress = if (progress != null || isFinished != null) {
            AbsMediaProgress(progress = progress, isFinished = isFinished)
        } else {
            null
        },
    )

    // =========================================================================
    // isAudiobookId — routing guard
    // =========================================================================

    @Test
    fun `isAudiobookId returns true for all audiobook node IDs`() {
        assertTrue(AudiobookAutoMediaMapper.isAudiobookId(AudiobookAutoMediaMapper.AUDIOBOOKS_ROOT_ID))
        assertTrue(AudiobookAutoMediaMapper.isAudiobookId("audiobooks_library_lib1"))
        assertTrue(AudiobookAutoMediaMapper.isAudiobookId("audiobooks_book_item1"))
    }

    @Test
    fun `isAudiobookId returns false for IPTV and music node IDs`() {
        // IPTV well-known nodes + prefixes
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("root"))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("favorites"))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("loved_songs"))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("custom_playlist_abc"))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("custom_group_xyz"))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("Sports"))
        // Music sub-tree
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId(MusicAutoMediaMapper.MUSIC_ROOT_ID))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("music_track_t1"))
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId("music_album_al1"))
        // Edge: empty string
        assertFalse(AudiobookAutoMediaMapper.isAudiobookId(""))
    }

    /** Cross guard: no audiobook ID may be swallowed by the music routing. */
    @Test
    fun `audiobook node IDs are never music IDs`() {
        assertFalse(MusicAutoMediaMapper.isMusicId(AudiobookAutoMediaMapper.AUDIOBOOKS_ROOT_ID))
        assertFalse(MusicAutoMediaMapper.isMusicId("audiobooks_library_lib1"))
        assertFalse(MusicAutoMediaMapper.isMusicId("audiobooks_book_item1"))
    }

    // =========================================================================
    // ID round-trip (build/parse)
    // =========================================================================

    @Test
    fun `libraryIdFrom strips the audiobooks_library_ prefix`() {
        val built = "${AudiobookAutoMediaMapper.AUDIOBOOK_LIBRARY_PREFIX}lib-42"
        assertEquals("lib-42", AudiobookAutoMediaMapper.libraryIdFrom(built))
    }

    @Test
    fun `bookIdFrom strips the audiobooks_book_ prefix`() {
        val built = "${AudiobookAutoMediaMapper.AUDIOBOOK_BOOK_PREFIX}li_abc123"
        assertEquals("li_abc123", AudiobookAutoMediaMapper.bookIdFrom(built))
    }

    @Test
    fun `library node IDs built by audiobooksRootChildren round-trip through libraryIdFrom`() {
        val items = AudiobookAutoMediaMapper.audiobooksRootChildren(
            bookLibraries = listOf(library("l1"), library("l2")),
            booksFor = { null },
        )
        assertEquals(listOf("l1", "l2"), items.map { AudiobookAutoMediaMapper.libraryIdFrom(it.mediaId) })
    }

    @Test
    fun `book item IDs round-trip through bookIdFrom`() {
        val items = AudiobookAutoMediaMapper.bookItems(listOf(book("b1"), book("b2")))
        assertEquals(listOf("b1", "b2"), items.map { AudiobookAutoMediaMapper.bookIdFrom(it.mediaId) })
    }

    // =========================================================================
    // audiobooksRootItem
    // =========================================================================

    @Test
    fun `audiobooksRootItem has correct ID, is browsable, is not playable`() {
        val item = AudiobookAutoMediaMapper.audiobooksRootItem()

        assertEquals(AudiobookAutoMediaMapper.AUDIOBOOKS_ROOT_ID, item.mediaId)
        assertTrue("audiobooksRootItem must be browsable", item.mediaMetadata.isBrowsable ?: false)
        assertFalse("audiobooksRootItem must not be playable", item.mediaMetadata.isPlayable ?: true)
        assertEquals("Audiobooks", item.mediaMetadata.title?.toString())
    }

    // =========================================================================
    // audiobooksRootChildren — library-picker vs flat-list decision
    // =========================================================================

    @Test
    fun `more than one library produces a browsable picker node per library`() {
        var booksForCalled = false
        val items = AudiobookAutoMediaMapper.audiobooksRootChildren(
            bookLibraries = listOf(library("l1", "Fiction"), library("l2", "Non-fiction")),
            booksFor = { booksForCalled = true; emptyList() },
        )

        assertEquals(2, items.size)
        assertEquals("audiobooks_library_l1", items[0].mediaId)
        assertEquals("audiobooks_library_l2", items[1].mediaId)
        assertEquals("Fiction", items[0].mediaMetadata.title?.toString())
        assertEquals("Non-fiction", items[1].mediaMetadata.title?.toString())
        items.forEach { item ->
            assertTrue("${item.mediaId} must be browsable", item.mediaMetadata.isBrowsable ?: false)
            assertFalse("${item.mediaId} must not be playable", item.mediaMetadata.isPlayable ?: true)
        }
        assertFalse("picker level must not resolve books", booksForCalled)
    }

    @Test
    fun `exactly one library goes straight to the flat playable book list`() {
        val items = AudiobookAutoMediaMapper.audiobooksRootChildren(
            bookLibraries = listOf(library("l1")),
            booksFor = { libraryId ->
                assertEquals("l1", libraryId)
                listOf(book("b1"), book("b2"))
            },
        )

        assertEquals(2, items.size)
        assertEquals("audiobooks_book_b1", items[0].mediaId)
        assertEquals("audiobooks_book_b2", items[1].mediaId)
        items.forEach { item ->
            assertTrue("${item.mediaId} must be playable", item.mediaMetadata.isPlayable ?: false)
            assertFalse("${item.mediaId} must not be browsable", item.mediaMetadata.isBrowsable ?: true)
        }
    }

    /** Books not fetched yet (or the fetch failed) → empty children, no crash. */
    @Test
    fun `single library with unfetched books returns empty children`() {
        val items = AudiobookAutoMediaMapper.audiobooksRootChildren(
            bookLibraries = listOf(library("l1")),
            booksFor = { null },
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `no book libraries returns empty children`() {
        val items = AudiobookAutoMediaMapper.audiobooksRootChildren(
            bookLibraries = emptyList(),
            booksFor = { listOf(book("b1")) },
        )
        assertEquals(0, items.size)
    }

    // =========================================================================
    // bookItems — playable rows with author + progress subtitle and cover art
    // =========================================================================

    @Test
    fun `bookItems maps title and falls back to Untitled`() {
        val items = AudiobookAutoMediaMapper.bookItems(listOf(book("b1", title = "Dune"), book("b2", title = null)))
        assertEquals("Dune", items[0].mediaMetadata.title?.toString())
        assertEquals("Untitled", items[1].mediaMetadata.title?.toString())
    }

    @Test
    fun `bookItems resolves cover art from the item id when a resolver is provided`() {
        val items = AudiobookAutoMediaMapper.bookItems(listOf(book("b1"))) { id ->
            Uri.parse("https://abs.example/api/items/$id/cover?token=t")
        }
        assertEquals(
            Uri.parse("https://abs.example/api/items/b1/cover?token=t"),
            items[0].mediaMetadata.artworkUri,
        )
    }

    @Test
    fun `bookItems leaves artwork null without a resolver`() {
        assertNull(AudiobookAutoMediaMapper.bookItems(listOf(book("b1")))[0].mediaMetadata.artworkUri)
    }

    @Test
    fun `bookItems sets the subtitle on both subtitle and artist fields`() {
        val item = AudiobookAutoMediaMapper.bookItems(
            listOf(book("b1", author = "Frank Herbert", progress = 0.45)),
        )[0]
        assertEquals("Frank Herbert · 45% listened", item.mediaMetadata.subtitle?.toString())
        assertEquals("Frank Herbert · 45% listened", item.mediaMetadata.artist?.toString())
    }

    // =========================================================================
    // bookSubtitle — progress hint formatting (shared progressBadge rules)
    // =========================================================================

    @Test
    fun `subtitle is author plus percent listened for an in-progress book`() {
        assertEquals(
            "Frank Herbert · 45% listened",
            AudiobookAutoMediaMapper.bookSubtitle(book("b1", author = "Frank Herbert", progress = 0.45)),
        )
    }

    @Test
    fun `subtitle is author plus Finished when the server flag is set`() {
        assertEquals(
            "Frank Herbert · Finished",
            AudiobookAutoMediaMapper.bookSubtitle(book("b1", author = "Frank Herbert", isFinished = true)),
        )
    }

    @Test
    fun `subtitle is author plus Finished at 99 percent progress without the flag`() {
        assertEquals(
            "Frank Herbert · Finished",
            AudiobookAutoMediaMapper.bookSubtitle(book("b1", author = "Frank Herbert", progress = 0.995)),
        )
    }

    @Test
    fun `subtitle is author only for an unstarted book`() {
        assertEquals(
            "Frank Herbert",
            AudiobookAutoMediaMapper.bookSubtitle(book("b1", author = "Frank Herbert")),
        )
    }

    @Test
    fun `subtitle is the progress hint alone when the author is missing`() {
        assertEquals(
            "45% listened",
            AudiobookAutoMediaMapper.bookSubtitle(book("b1", author = null, progress = 0.45)),
        )
    }

    @Test
    fun `subtitle is null when neither author nor progress exists`() {
        assertNull(AudiobookAutoMediaMapper.bookSubtitle(book("b1", author = null)))
    }
}
