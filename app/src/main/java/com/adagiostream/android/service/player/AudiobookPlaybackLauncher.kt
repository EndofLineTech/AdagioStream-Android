package com.adagiostream.android.service.player

import com.adagiostream.android.model.Account

/**
 * Starts (or resumes) playback of an audiobook — the seam between the
 * browsing UI (beads_adagio-59p.1.4) and the playback engine (59p.1.5).
 * Bound in [com.adagiostream.android.di.AudiobookshelfModule] to a delegate
 * of AudiobookPlaybackCoordinator.playAudiobook; the UI layer never touches
 * the coordinator directly.
 *
 * @param account the Audiobookshelf account to play through.
 * @param libraryItemId the book to play.
 * @param resumeOverride optional book-global start override in seconds (e.g.
 *   a chapter tap); null resumes from the server's `/play` position.
 */
fun interface AudiobookPlaybackLauncher {
    suspend fun play(account: Account, libraryItemId: String, resumeOverride: Double?)
}
