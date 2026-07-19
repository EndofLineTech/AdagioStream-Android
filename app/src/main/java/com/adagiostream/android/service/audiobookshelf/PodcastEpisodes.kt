package com.adagiostream.android.service.audiobookshelf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure podcast episode ordering / progress / partition core
 * (beads_adagio-59p.2.1) — port of iOS `PodcastPlaybackContext`,
 * `PodcastRecentEpisodes` and `PodcastContinueListening`. Everything here is
 * side-effect free so the sort/partition/finished rules are directly
 * unit-testable, and so the follow-up playback bead (59p.2.2) can drive
 * auto-play-next through the SAME [sortedEpisodes] seam the UI displays —
 * display order and auto-play direction can never disagree.
 */

/**
 * Which direction "next episode" walks a show's episode list. Serial names
 * match the iOS raw values ("newestFirst"/"oldestFirst") so exports stay
 * comparable. Persisted in `AppSettings.podcastEpisodeSortOrder`.
 */
@Serializable
enum class PodcastEpisodeOrder(val displayName: String) {
    @SerialName("newestFirst")
    NEWEST_FIRST("Newest First"),

    @SerialName("oldestFirst")
    OLDEST_FIRST("Oldest First"),
}

/**
 * Threshold above which a progress record counts as finished even without the
 * server's `isFinished` flag — a sync can land a hair under 1.0 (the last
 * report before the file's end event) without the flag ever being set. Shared
 * with the audiobooks UI (`isFinishedBook`) so books and episodes agree.
 */
const val FINISHED_PROGRESS = 0.99

/**
 * Whether a hydrated progress record counts as finished: the server's own
 * flag, OR progress >= [FINISHED_PROGRESS]. `null` (never started, or a 404'd
 * fetch) is unplayed — never finished. Pure.
 */
fun isFinishedProgress(progress: AbsMediaProgress?): Boolean =
    progress != null &&
        (progress.isFinished == true || (progress.progress ?: 0.0) >= FINISHED_PROGRESS)

/**
 * Parses an ABS `pubDate` (RFC-2822, "Mon, 02 Jan 2006 15:04:05 GMT") to
 * epoch millis. `null` when absent/unparseable. THE one pubDate parser —
 * sorting and the row display formatter both route through it, so the format
 * string can't drift between them.
 */
fun parsePubDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    // SimpleDateFormat is not thread-safe — a fresh instance per call is the
    // cheap correct option at list-sort scale. isLenient=false matches iOS's
    // strict DateFormatter: a rolled-over day (45 Jan) or two-digit year
    // returns null instead of a confidently-wrong parsed date.
    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    format.isLenient = false
    return format.parse(raw, ParsePosition(0))?.time
}

/** Localized display date for an episode's `pubDate`, or `null` (show
 *  nothing) rather than mangled text when the format doesn't match. */
fun formatPubDate(raw: String?): String? {
    val millis = parsePubDate(raw) ?: return null
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        .format(java.util.Date(millis))
}

/**
 * Sorts a show's episodes per [order] by PARSED `pubDate` — ABS's
 * `media.episodes[]` association order is insertion/PK order, NOT guaranteed
 * chronological, so reversing wire order could mislabel newest/oldest.
 * Episodes with a missing/unparseable `pubDate` sort LAST in stable wire
 * order (Kotlin's sort is stable), so the order is total and deterministic.
 *
 * The single seam the By Show list, Recent Episodes, and (in 59p.2.2)
 * whole-show auto-play-next all call through.
 */
fun sortedEpisodes(episodes: List<AbsEpisode>, order: PodcastEpisodeOrder): List<AbsEpisode> {
    val keyed = episodes.map { it to parsePubDate(it.pubDate) }
    val sorted = keyed.sortedWith { a, b ->
        val da = a.second
        val db = b.second
        when {
            da != null && db != null ->
                if (order == PodcastEpisodeOrder.NEWEST_FIRST) db.compareTo(da) else da.compareTo(db)
            da == null && db == null -> 0
            da == null -> 1 // undated sorts after dated
            else -> -1
        }
    }
    return sorted.map { it.first }
}

/**
 * A podcast show summary for the By Show grid — just enough to render a tile
 * and open the show's episode list. `id` is the library item id.
 */
data class PodcastShow(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String?,
) {
    companion object {
        /** Builds a show from a (minified) library item, or `null` without a
         *  title (shouldn't happen for a real podcast; keeps this total). */
        fun from(item: AbsLibraryItem, libraryIdFallback: String): PodcastShow? {
            val title = item.media?.metadata?.title ?: return null
            return PodcastShow(
                id = item.id,
                libraryId = item.libraryId ?: libraryIdFallback,
                title = title,
                author = item.media.metadata.displayAuthor,
            )
        }
    }
}

/**
 * One episode plus the show it belongs to — the unit the Recent Episodes list
 * and the Continue Listening shelf render. Progress is NOT embedded anywhere
 * in ABS's episode objects (neither `media.episodes[]` nor `recentEpisode`
 * carry `userMediaProgress`) — it's hydrated separately via
 * `GET /api/me/progress/{libraryItemId}/{episodeId}` ([PodcastProgressHydrator]).
 */
data class PodcastEpisodeEntry(
    val episode: AbsEpisode,
    val showLibraryItemId: String,
    val showTitle: String?,
)

/**
 * The badge/progress state of one episode row, mapped from a HYDRATED
 * progress record via [episodeProgressState].
 */
sealed class EpisodeProgressState {
    /** Never started — drives the unplayed dot. */
    data object Unplayed : EpisodeProgressState()

    /** Started, unfinished — drives the progress bar. */
    data class InProgress(val fraction: Double) : EpisodeProgressState()

    /** Finished (flag or >= [FINISHED_PROGRESS]) — drives the Played badge. */
    data object Finished : EpisodeProgressState()
}

/**
 * Maps a hydrated progress record (null = the server 404'd → never started)
 * to its row display state. Pure — this was the OPEN iOS follow-up (list-view
 * progress hydration), implemented here.
 */
fun episodeProgressState(progress: AbsMediaProgress?): EpisodeProgressState = when {
    isFinishedProgress(progress) -> EpisodeProgressState.Finished
    (progress?.progress ?: 0.0) > 0.0 ->
        EpisodeProgressState.InProgress((progress?.progress ?: 0.0).coerceIn(0.0, 1.0))
    else -> EpisodeProgressState.Unplayed
}

/** A `GET /api/me/items-in-progress` response split into books and podcast
 *  episodes — see [partitionItemsInProgress]. */
data class InProgressPartition(
    val books: List<AbsLibraryItem>,
    val episodes: List<PodcastEpisodeEntry>,
)

/**
 * Splits the mixed items-in-progress response. ABS sends an in-progress
 * podcast as a minified show item with the played episode under a TOP-LEVEL
 * `recentEpisode` field — its presence is the exact discriminator ABS itself
 * uses (a book item never carries it; an episode-count heuristic could trip
 * on a single-file audiobook). Books keep the started/not-finished shelf
 * filter; episodes pass through unfiltered (the server only returns
 * in-progress items) with progress hydrated later. Server order (most recent
 * first) is preserved within each group. Pure — the single seam the
 * audiobooks shelf ([continueListeningBooks][com.adagiostream.android.ui
 * .screens.audiobooks.continueListeningBooks]) and the podcast shelf share.
 */
fun partitionItemsInProgress(items: List<AbsLibraryItem>): InProgressPartition {
    val books = mutableListOf<AbsLibraryItem>()
    val episodes = mutableListOf<PodcastEpisodeEntry>()
    for (item in items) {
        val recent = item.recentEpisode
        if (recent != null) {
            episodes.add(
                PodcastEpisodeEntry(
                    episode = recent,
                    showLibraryItemId = item.id,
                    showTitle = item.media?.metadata?.title,
                ),
            )
        } else {
            val progress = item.userMediaProgress?.progress ?: 0.0
            if (progress > 0.0 && !isFinishedProgress(item.userMediaProgress)) {
                books.add(item)
            }
        }
    }
    return InProgressPartition(books = books, episodes = episodes)
}
