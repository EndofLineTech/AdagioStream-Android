package com.adagiostream.android.service.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.adagiostream.android.service.audiobookshelf.AbsEpisode
import com.adagiostream.android.service.audiobookshelf.AbsLibrary
import com.adagiostream.android.service.audiobookshelf.PodcastShow
import com.adagiostream.android.service.audiobookshelf.formatPubDate
import com.adagiostream.android.ui.screens.audiobooks.formatBookDuration

/**
 * Pure helper that maps Audiobookshelf podcast domain objects to namespaced
 * Media3 [MediaItem]s for the Android Auto podcast browse tree
 * (beads_adagio-59p.2.4). Mirrors [AudiobookAutoMediaMapper] exactly: all IDs
 * use a `podcasts*` prefix so they never collide with the audiobook, music, or
 * IPTV node IDs, and the object is stateless so it unit-tests on the JVM
 * without the service stack.
 *
 * Browse shape (iOS CarPlayTemplateManager+Podcasts parity):
 * one root "Podcasts" node → a library picker level ONLY when more than one
 * podcast library exists → the show list (title, author, cover) → the show's
 * episode list (title, pubDate + duration, cover). Tapping an episode plays it
 * via the shared [PodcastPlaybackLauncher] path with the show's full context so
 * whole-show auto-play-next works from the car exactly like the phone.
 */
object PodcastAutoMediaMapper {

    // -------------------------------------------------------------------------
    // Media ID constants — all podcast-tree IDs start with "podcasts"
    // -------------------------------------------------------------------------

    const val PODCASTS_ROOT_ID = "podcasts"
    const val PODCAST_LIBRARY_PREFIX = "podcasts_library_"
    const val PODCAST_SHOW_PREFIX = "podcasts_show_"
    const val PODCAST_EPISODE_PREFIX = "podcasts_episode_"

    /**
     * Separator packing the show id and episode id into one episode node id.
     * ABS ids are nanoid-style (`A-Za-z0-9_-`) so a pipe can never appear in
     * either half — the show and episode ids round-trip cleanly even though
     * both can contain underscores (which rules out `_` as the separator).
     */
    private const val EPISODE_ID_SEPARATOR = '|'

    // -------------------------------------------------------------------------
    // ID routing helpers
    // -------------------------------------------------------------------------

    /** Returns true when [id] belongs to the podcast browse sub-tree. */
    fun isPodcastId(id: String): Boolean =
        id == PODCASTS_ROOT_ID ||
        id.startsWith(PODCAST_LIBRARY_PREFIX) ||
        id.startsWith(PODCAST_SHOW_PREFIX) ||
        id.startsWith(PODCAST_EPISODE_PREFIX)

    /** Extracts the raw library ID from a `podcasts_library_<id>` media ID. */
    fun libraryIdFrom(mediaId: String): String = mediaId.removePrefix(PODCAST_LIBRARY_PREFIX)

    /** Extracts the raw show (library-item) ID from a `podcasts_show_<id>` media ID. */
    fun showIdFrom(mediaId: String): String = mediaId.removePrefix(PODCAST_SHOW_PREFIX)

    /** Builds the episode node id carrying BOTH the show id and the episode id. */
    fun episodeMediaId(showId: String, episodeId: String): String =
        "$PODCAST_EPISODE_PREFIX$showId$EPISODE_ID_SEPARATOR$episodeId"

    /**
     * Splits a `podcasts_episode_<show>|<episode>` media ID back into its
     * (showId, episodeId) pair. The pipe never appears inside either half, so
     * splitting on the first one is unambiguous.
     */
    fun episodeIdsFrom(mediaId: String): Pair<String, String> {
        val body = mediaId.removePrefix(PODCAST_EPISODE_PREFIX)
        val showId = body.substringBefore(EPISODE_ID_SEPARATOR)
        val episodeId = body.substringAfter(EPISODE_ID_SEPARATOR)
        return showId to episodeId
    }

    // -------------------------------------------------------------------------
    // Root node — the "Podcasts" entry in the Root browse list
    // -------------------------------------------------------------------------

    /** The "Podcasts" entry as a browsable MediaItem for the Root list. */
    fun podcastsRootItem(): MediaItem = buildBrowsable(PODCASTS_ROOT_ID, "Podcasts", null)

    /**
     * Children of the "Podcasts" root, given the already-fetched podcast
     * [podcastLibraries] (iOS parity):
     *
     *  - more than one library → one browsable node per library (picker level)
     *  - exactly one library   → straight to that library's show list; [showsFor]
     *    returns the cached shows for a library ID, or null when not fetched yet
     *    → empty children until the caller's background fetch fires
     *    notifyChildrenChanged
     *  - no podcast libraries   → empty (root node isn't even shown in that case)
     */
    fun podcastsRootChildren(
        podcastLibraries: List<AbsLibrary>,
        showsFor: (String) -> List<PodcastShow>?,
        coverArtUri: (String) -> Uri? = { null },
    ): List<MediaItem> = when {
        podcastLibraries.size > 1 -> podcastLibraries.map { library ->
            buildBrowsable("$PODCAST_LIBRARY_PREFIX${library.id}", library.name, null)
        }
        podcastLibraries.size == 1 ->
            showItems(showsFor(podcastLibraries[0].id) ?: emptyList(), coverArtUri)
        else -> emptyList()
    }

    // -------------------------------------------------------------------------
    // Show rows
    // -------------------------------------------------------------------------

    /**
     * Maps [shows] to browsable (drill into episodes) items: title, author
     * subtitle, and cover art resolved through [coverArtUri] (account-derived
     * tokened URL — never logged).
     */
    fun showItems(shows: List<PodcastShow>, coverArtUri: (String) -> Uri? = { null }): List<MediaItem> =
        shows.map { show ->
            val subtitle = show.author?.takeIf { it.isNotEmpty() }
            MediaItem.Builder()
                .setMediaId("$PODCAST_SHOW_PREFIX${show.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(show.title)
                        .apply { if (subtitle != null) setSubtitle(subtitle) }
                        .apply { coverArtUri(show.id)?.let { setArtworkUri(it) } }
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                        .build()
                )
                .build()
        }

    // -------------------------------------------------------------------------
    // Episode rows
    // -------------------------------------------------------------------------

    /**
     * Maps a show's [episodes] to playable (never browsable) items: episode
     * title, "pubDate · duration" subtitle ([episodeSubtitle]), show title as
     * the artist, and the show cover ([coverArtUri] resolves against the SHOW's
     * id, since ABS serves one cover per show). The media id carries both
     * [showId] and the episode id so the tap handler can rebuild the playback
     * context.
     */
    fun episodeItems(
        showId: String,
        showTitle: String?,
        episodes: List<AbsEpisode>,
        coverArtUri: (String) -> Uri? = { null },
    ): List<MediaItem> = episodes.map { episode ->
        val subtitle = episodeSubtitle(episode)
        MediaItem.Builder()
            .setMediaId(episodeMediaId(showId, episode.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title ?: "Episode")
                    .apply { showTitle?.takeIf { it.isNotEmpty() }?.let { setArtist(it) } }
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .apply { coverArtUri(showId)?.let { setArtworkUri(it) } }
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build()
            )
            .build()
    }

    /**
     * Episode row subtitle: publish date plus duration, joined with " · " — the
     * exact iOS `podcastEpisodeRowDetail` shape, routed through the SAME
     * [formatPubDate] / [formatBookDuration] the phone episode row uses. Null
     * when neither part exists.
     */
    fun episodeSubtitle(episode: AbsEpisode): String? {
        val date = formatPubDate(episode.pubDate)
        val duration = episode.duration?.let { formatBookDuration(it) }
        return listOfNotNull(date, duration)
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
