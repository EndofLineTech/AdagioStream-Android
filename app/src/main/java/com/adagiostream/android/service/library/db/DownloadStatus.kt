package com.adagiostream.android.service.library.db

/**
 * Canonical download lifecycle states for the `downloads` table (baw.6.1).
 *
 * Stored as TEXT in the `downloads.status` column. Room cannot express a SQL
 * `CHECK(status IN (...))` constraint via annotations, so the allowed set is
 * enforced in code through these constants — never write a raw status string.
 *
 * State machine (see [com.adagiostream.android.service.download.DownloadStateMachine]):
 * ```
 *   queued ──▶ downloading ──▶ completed
 *                 │   ▲
 *                 ▼   │ retry (resumes from resumeOffset)
 *              failed / paused
 * ```
 */
object DownloadStatus {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"

    /** Statuses that represent in-flight or resumable work (the "active" set). */
    val ACTIVE: Set<String> = setOf(QUEUED, DOWNLOADING, PAUSED)

    /** All valid status values — the CHECK set Room cannot enforce at the schema level. */
    val ALL: Set<String> = setOf(QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED)

    /** True when [status] is one Room/code should accept. */
    fun isValid(status: String): Boolean = status in ALL
}
