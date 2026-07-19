package com.adagiostream.android.service.audiobookshelf

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 * What to auto-play when a podcast episode ends (beads_adagio-59p.2.2, iOS
 * parity: `PodcastEpisodeEndBehavior`). Serial names match the iOS raw values
 * so exports stay comparable. Default is [NEXT_UNPLAYED] (iOS default): it can
 * only advance into the future, so finishing the newest episode stops instead
 * of replaying something older.
 */
@Serializable(with = PodcastEpisodeEndBehaviorSerializer::class)
enum class PodcastEpisodeEndBehavior(val serialName: String, val displayName: String) {
    /** Stop at the end of the episode. No auto-advance. */
    STOP("stop", "Stop"),

    /** Closest strictly-newer-by-pubDate unfinished episode; undated episodes
     *  are never selected. */
    NEXT_UNPLAYED("nextUnplayed", "Next Unplayed"),

    /** Walk the display order ([PodcastEpisodeOrder] via [sortedEpisodes])
     *  skipping finished episodes; stop when none remain. */
    CONTINUE_IN_SORT_ORDER("continueInSortOrder", "Sort Order"),
}

/**
 * Tolerant serializer: an unknown/garbage stored value decodes to the default
 * ([PodcastEpisodeEndBehavior.NEXT_UNPLAYED]) instead of failing the whole
 * AppSettings decode — same pattern as `SXMMetadataSourceSerializer`.
 */
object PodcastEpisodeEndBehaviorSerializer : KSerializer<PodcastEpisodeEndBehavior> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PodcastEpisodeEndBehavior", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PodcastEpisodeEndBehavior) =
        encoder.encodeString(value.serialName)

    override fun deserialize(decoder: Decoder): PodcastEpisodeEndBehavior {
        val raw = decoder.decodeString()
        return PodcastEpisodeEndBehavior.entries.firstOrNull { it.serialName == raw }
            ?: PodcastEpisodeEndBehavior.NEXT_UNPLAYED
    }
}

/**
 * The show's episode list + display order — enough state to answer "what
 * plays after this episode ends" without re-fetching the show (iOS parity:
 * `PodcastPlaybackContext`). Carried inside the coordinator's active session
 * by beads_adagio-59p.2.2.
 */
data class PodcastPlaybackContext(
    /** The show's library item id. */
    val libraryItemId: String,
    val showTitle: String?,
    /** Episodes in server wire order (sorting happens per-behavior). */
    val episodes: List<AbsEpisode>,
    val order: PodcastEpisodeOrder,
)

/**
 * The episode that plays after [after] ends, per [behavior]. `null` means
 * stop — either the policy says so ([PodcastEpisodeEndBehavior.STOP]) or
 * nothing left qualifies. Pure — [isFinished] is an injected predicate so the
 * caller can hydrate per-episode progress on demand (see [selectNextEpisode]).
 *
 * - [PodcastEpisodeEndBehavior.NEXT_UNPLAYED]: closest strictly-newer-by-
 *   `pubDate` unfinished episode, regardless of display [order]. Undated
 *   episodes have no comparable `pubDate` and are never selected; an undated
 *   CURRENT episode yields null.
 * - [PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER]: first unfinished
 *   episode walking [order] forward from [after] in [sortedEpisodes].
 */
fun nextEpisode(
    episodes: List<AbsEpisode>,
    after: String,
    order: PodcastEpisodeOrder,
    behavior: PodcastEpisodeEndBehavior,
    isFinished: (String) -> Boolean,
): AbsEpisode? = when (behavior) {
    PodcastEpisodeEndBehavior.STOP -> null

    PodcastEpisodeEndBehavior.NEXT_UNPLAYED -> {
        val currentDate = episodes.firstOrNull { it.id == after }?.let { parsePubDate(it.pubDate) }
        if (currentDate == null) {
            null
        } else {
            episodes
                .mapNotNull { ep -> parsePubDate(ep.pubDate)?.takeIf { it > currentDate }?.let { ep to it } }
                .filterNot { isFinished(it.first.id) }
                .minByOrNull { it.second }
                ?.first
        }
    }

    PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER -> {
        val ordered = sortedEpisodes(episodes, order)
        val pos = ordered.indexOfFirst { it.id == after }
        if (pos < 0) null else ordered.drop(pos + 1).firstOrNull { !isFinished(it.id) }
    }
}

/**
 * Resolves the next episode to auto-play, hydrating per-episode progress ONLY
 * as needed (iOS parity: `AudioPlayerService.nextPodcastEpisode`). [nextEpisode]
 * is the pure selector; this loop feeds it a growing finished-id set, fetching
 * one new candidate's progress per iteration via [progressLookup], until the
 * selector converges on an episode already known unfinished or returns null.
 * Bounded by the show's episode count — each iteration hydrates a distinct id.
 */
suspend fun selectNextEpisode(
    context: PodcastPlaybackContext,
    after: String,
    behavior: PodcastEpisodeEndBehavior,
    progressLookup: suspend (episodeId: String) -> AbsMediaProgress?,
): AbsEpisode? {
    val finishedIds = mutableSetOf<String>()
    val checkedIds = mutableSetOf<String>()
    while (true) {
        val candidate = nextEpisode(context.episodes, after, context.order, behavior) {
            it in finishedIds
        } ?: return null
        // Already hydrated and known unfinished — the selector is stably
        // re-selecting it, so it's the answer.
        if (!checkedIds.add(candidate.id)) return candidate
        if (isFinishedProgress(progressLookup(candidate.id))) {
            finishedIds.add(candidate.id) // re-run the selector so it skips this one
        } else {
            return candidate
        }
    }
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
