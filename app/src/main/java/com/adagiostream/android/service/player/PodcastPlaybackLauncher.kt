package com.adagiostream.android.service.player

import com.adagiostream.android.model.Account
import com.adagiostream.android.service.audiobookshelf.PodcastPlaybackContext

/**
 * Starts playback of one podcast episode — the seam between the podcast UI
 * (beads_adagio-59p.2.1) and the playback engine (59p.2.2), mirroring
 * [AudiobookPlaybackLauncher]. Bound in
 * [com.adagiostream.android.di.AudiobookshelfModule] to a delegate of
 * AudiobookPlaybackCoordinator.playPodcastEpisode; the UI layer never touches
 * the coordinator directly.
 *
 * @param account the Audiobookshelf account to play through.
 * @param showLibraryItemId the show's library item id.
 * @param episodeId the episode to play.
 * @param context the show's episode list + display order for auto-play-next;
 *   null (shelf/recent taps with no list in hand) makes the coordinator fetch
 *   the show detail itself.
 */
fun interface PodcastPlaybackLauncher {
    suspend fun play(
        account: Account,
        showLibraryItemId: String,
        episodeId: String,
        context: PodcastPlaybackContext?,
    )
}
