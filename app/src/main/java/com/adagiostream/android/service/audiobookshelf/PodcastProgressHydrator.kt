package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * On-demand per-episode progress hydration (beads_adagio-59p.2.1).
 *
 * ABS embeds episode progress NOWHERE in the episode objects — the only real
 * source is `GET /api/me/progress/{libraryItemId}/{episodeId}` (404 = never
 * started → the API returns `null`). List rows request hydration as they
 * become visible (LazyColumn only composes visible rows, so composition IS
 * the visibility signal); this class dedupes, caches, and bounds the fan-out.
 *
 * - [progress] maps "showId/episodeId" → the fetched record (`null` value =
 *   hydrated, unplayed). A key ABSENT from the map has not been hydrated yet.
 * - Each key is fetched at most once per instance ([hydrate] is a no-op for
 *   keys already fetched or in flight); a FAILED fetch un-marks the key so a
 *   later scroll-past retries.
 * - Concurrency ceiling: at most [concurrency] requests in flight — a page of
 *   visible rows (~15) hydrates in a few short waves instead of N parallel
 *   sockets per frame.
 *
 * ponytail: whole-instance cache, no TTL — progress staleness across a long
 * session is acceptable for list badges; 59p.2.2's playback sync is the
 * authoritative writer. Add invalidation there if badges must react live.
 */
class PodcastProgressHydrator(
    private val fetch: suspend (libraryItemId: String, episodeId: String) -> AbsMediaProgress?,
    concurrency: Int = 4,
) {
    private val semaphore = Semaphore(concurrency)
    private val mutex = Mutex()
    private val requested = mutableSetOf<String>()

    private val _progress = MutableStateFlow<Map<String, AbsMediaProgress?>>(emptyMap())

    /** Hydrated records keyed by [key]. Absent key = not yet hydrated. */
    val progress: StateFlow<Map<String, AbsMediaProgress?>> = _progress.asStateFlow()

    companion object {
        /** The cache key for one (show, episode) pair. */
        fun key(libraryItemId: String, episodeId: String): String = "$libraryItemId/$episodeId"
    }

    /**
     * Fetches progress for every not-yet-requested key in [keys]
     * (libraryItemId → episodeId pairs), bounded-parallel. Suspends until the
     * batch completes; results stream into [progress] as they land.
     */
    suspend fun hydrate(keys: List<Pair<String, String>>) = coroutineScope {
        val fresh = mutex.withLock { keys.filter { requested.add(key(it.first, it.second)) } }
        fresh.map { (itemId, episodeId) ->
            async {
                semaphore.withPermit {
                    val record = try {
                        fetch(itemId, episodeId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Transient failure: un-mark so a later pass retries,
                        // leave the key unhydrated (row shows no badge).
                        mutex.withLock { requested.remove(key(itemId, episodeId)) }
                        return@withPermit
                    }
                    _progress.update { it + (key(itemId, episodeId) to record) }
                }
            }
        }.awaitAll()
    }
}
