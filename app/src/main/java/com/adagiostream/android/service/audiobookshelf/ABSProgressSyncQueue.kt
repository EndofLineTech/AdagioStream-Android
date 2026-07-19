package com.adagiostream.android.service.audiobookshelf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk-persisted offline progress queue for Audiobookshelf — port of iOS
 * `ABSProgressSyncQueue` (beads_adagio-59p.1.5).
 *
 * When the server is unreachable (or a session sync fails), progress updates
 * are queued here instead of dropped, then flushed in one
 * `PATCH /api/me/progress/batch/update` when connectivity returns (app
 * background, or before opening a new playback session).
 *
 * Semantics:
 *  - ONE entry per `(libraryItemId, episodeId)` pair — last-writer-wins by
 *    [AbsProgressUpdate.lastUpdate], so a late-arriving stale update can never
 *    rewind a newer queued position.
 *  - [flush] clears ONLY the exact snapshot it sent: entries enqueued while
 *    the batch request is in flight survive for the next flush.
 *  - [pendingPosition] seeds resume so reconnecting never rewinds behind a
 *    position reached offline.
 *
 * Persistence is a plain JSON array in [file] (app files dir), loaded on
 * construction; the file is deleted when the queue drains. Corrupt/unreadable
 * files degrade to an empty queue.
 */
class ABSProgressSyncQueue(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var pending: List<AbsProgressUpdate> = load()

    private fun load(): List<AbsProgressUpdate> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Best-effort persist — a failed disk write must never break playback.
     * Writes a sibling temp file then renames it over the target (review M3):
     * [load] degrades corrupt JSON to an empty queue, so a crash mid-write on
     * the real file would silently drop ALL queued progress.
     */
    private fun persist() {
        try {
            if (pending.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(pending))
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse rename-over-existing; fall back.
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /**
     * Adds [update], deduping to one entry per `(libraryItemId, episodeId)`
     * and keeping the entry with the newest [AbsProgressUpdate.lastUpdate]
     * (a stale update never replaces a newer queued one).
     */
    fun enqueue(update: AbsProgressUpdate) {
        synchronized(lock) {
            val existing = pending.firstOrNull { it.sameItemAs(update) }
            if (existing != null && existing.lastUpdate >= update.lastUpdate) return
            pending = pending.filterNot { it.sameItemAs(update) } + update
            persist()
        }
    }

    /**
     * The queued (not yet flushed) global position for a book/episode, or null
     * when nothing is queued for it — resume precedence level 2 (bead .1.5).
     */
    fun pendingPosition(libraryItemId: String, episodeId: String? = null): Double? =
        synchronized(lock) {
            pending.firstOrNull { it.libraryItemId == libraryItemId && it.episodeId == episodeId }
        }?.currentTime

    /** Current queue contents (immutable snapshot). */
    fun snapshot(): List<AbsProgressUpdate> = synchronized(lock) { pending }

    val isEmpty: Boolean get() = synchronized(lock) { pending.isEmpty() }

    /**
     * Sends the current queue in one batch request. On success (2xx) removes
     * ONLY the entries that were in the sent snapshot — anything enqueued
     * during the in-flight request survives. On any failure the whole queue is
     * kept for the next attempt. Returns whether the queue's snapshot made it
     * to the server (trivially true when empty).
     */
    suspend fun flush(api: AudiobookshelfApi): Boolean {
        val batch = synchronized(lock) { pending }
        if (batch.isEmpty()) return true
        return try {
            api.batchUpdateProgress(batch)
            synchronized(lock) {
                pending = pending.filterNot { it in batch }
                persist()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun AbsProgressUpdate.sameItemAs(other: AbsProgressUpdate): Boolean =
        libraryItemId == other.libraryItemId && episodeId == other.episodeId
}
