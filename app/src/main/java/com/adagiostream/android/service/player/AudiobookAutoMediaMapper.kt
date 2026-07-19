package com.adagiostream.android.service.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.ui.screens.audiobooks.progressBadge

/**
 * Pure helper that maps Audiobookshelf domain objects to namespaced Media3
 * [MediaItem]s for the Android Auto audiobook browse tree
 * (beads_adagio-59p.1.7). Mirrors [MusicAutoMediaMapper] for the Navidrome
 * tree: all IDs use an `audiobooks*` prefix so they never collide with the
 * IPTV node IDs or the `music_*` sub-tree, and the object is stateless so it
 * unit-tests on the JVM without the service stack.
 *
 * Browse shape (iOS CarPlayTemplateManager+Audiobooks parity):
 * one root "Audiobooks" node → a library picker level ONLY when more than one
 * book-type library exists → the flat book list (title, author + progress
 * hint, cover). Tapping a book resumes via the shared
 * [AudiobookPlaybackLauncher] path.
 */
object AudiobookAutoMediaMapper {

    // -------------------------------------------------------------------------
    // Media ID constants — all audiobook-tree IDs start with "audiobooks"
    // -------------------------------------------------------------------------

    const val AUDIOBOOKS_ROOT_ID = "audiobooks"
    const val AUDIOBOOK_LIBRARY_PREFIX = "audiobooks_library_"
    const val AUDIOBOOK_BOOK_PREFIX = "audiobooks_book_"

    // -------------------------------------------------------------------------
    // ID routing helpers
    // -------------------------------------------------------------------------

    /** Returns true when [id] belongs to the audiobook browse sub-tree. */
    fun isAudiobookId(id: String): Boolean =
        id == AUDIOBOOKS_ROOT_ID ||
        id.startsWith(AUDIOBOOK_LIBRARY_PREFIX) ||
        id.startsWith(AUDIOBOOK_BOOK_PREFIX)

    /** Extracts the raw library ID from an `audiobooks_library_<id>` media ID. */
    fun libraryIdFrom(mediaId: String): String = mediaId.removePrefix(AUDIOBOOK_LIBRARY_PREFIX)

    /** Extracts the raw library-item ID from an `audiobooks_book_<id>` media ID. */
    fun bookIdFrom(mediaId: String): String = mediaId.removePrefix(AUDIOBOOK_BOOK_PREFIX)

    // -------------------------------------------------------------------------
    // Root node — the "Audiobooks" entry in the Root browse list
    // -------------------------------------------------------------------------

    /**
     * Returns the "Audiobooks" entry as a browsable MediaItem for the Root
     * list. Only shown when an enabled Audiobookshelf account exists.
     */
    fun audiobooksRootItem(): MediaItem = buildBrowsable(AUDIOBOOKS_ROOT_ID, "Audiobooks", null)

    /**
     * Children of the "Audiobooks" root, given the already-fetched book-type
     * [bookLibraries] (iOS parity, ciu.1/6z5):
     *
     *  - more than one library → one browsable node per library (picker level)
     *  - exactly one library   → straight to that library's flat book list
     *    (no pointless drill-down); [booksFor] returns the cached books for a
     *    library ID, or null when not fetched yet → empty children until the
     *    caller's background fetch lands and fires notifyChildrenChanged
     *  - no book libraries     → empty (Auto shows its empty state)
     */
    fun audiobooksRootChildren(
        bookLibraries: List<AbsLibrary>,
        booksFor: (String) -> List<AbsLibraryItem>?,
        coverArtUri: (String) -> Uri? = { null },
    ): List<MediaItem> = when {
        bookLibraries.size > 1 -> bookLibraries.map { library ->
            buildBrowsable("$AUDIOBOOK_LIBRARY_PREFIX${library.id}", library.name, null)
        }
        bookLibraries.size == 1 ->
            bookItems(booksFor(bookLibraries[0].id) ?: emptyList(), coverArtUri)
        else -> emptyList()
    }

    // -------------------------------------------------------------------------
    // Book rows
    // -------------------------------------------------------------------------

    /**
     * Maps [books] to playable (never browsable) items: title, author +
     * progress-hint subtitle ([bookSubtitle]), and cover art resolved through
     * [coverArtUri] (account-derived tokened URL — never logged).
     */
    fun bookItems(books: List<AbsLibraryItem>, coverArtUri: (String) -> Uri? = { null }): List<MediaItem> =
        books.map { book ->
            val subtitle = bookSubtitle(book)
            MediaItem.Builder()
                .setMediaId("$AUDIOBOOK_BOOK_PREFIX${book.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.media?.metadata?.title ?: "Untitled")
                        .apply { if (subtitle != null) { setArtist(subtitle); setSubtitle(subtitle) } }
                        .apply { coverArtUri(book.id)?.let { setArtworkUri(it) } }
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                        .build()
                )
                .build()
        }

    /**
     * Row subtitle: author plus a progress hint ("Finished" / "N% listened"
     * via the shared [progressBadge] rules), joined with " · " — the exact
     * iOS `audiobookRowDetail` shape. Null when neither part exists.
     */
    fun bookSubtitle(item: AbsLibraryItem): String? {
        val author = item.media?.metadata?.displayAuthor?.takeIf { it.isNotEmpty() }
        return listOfNotNull(author, progressBadge(item))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
    }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    private fun buildBrowsable(id: String, title: String, subtitle: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .build()
            )
            .build()
}
