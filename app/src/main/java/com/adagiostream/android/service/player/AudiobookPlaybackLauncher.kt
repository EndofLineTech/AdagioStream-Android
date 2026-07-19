package com.adagiostream.android.service.player

import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi

/**
 * Starts (or resumes) playback of an audiobook — the single integration point
 * between the browsing UI (beads_adagio-59p.1.4) and the playback engine.
 *
 * ponytail: bound to a no-op in [com.adagiostream.android.di.AudiobookshelfModule]
 * until the playback branch (59p.1.5) lands a real implementation — that branch
 * replaces the binding, nothing else changes.
 *
 * @param item the book to play; carries [AbsLibraryItem.userMediaProgress] so
 *   the implementation can resume from the server position.
 * @param startTimeSeconds optional book-global start override (e.g. a chapter
 *   tap); null means resume from the server's `/play` position.
 */
fun interface AudiobookPlaybackLauncher {
    fun play(item: AbsLibraryItem, api: AudiobookshelfApi, startTimeSeconds: Double?)
}
