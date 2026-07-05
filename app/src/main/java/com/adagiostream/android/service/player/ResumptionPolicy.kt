package com.adagiostream.android.service.player

import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.persistence.LastPlayed

/**
 * Pure, fully unit-testable routing for Media3's `onPlaybackResumption`
 * callback (baw.10 — fired when a fresh MediaController — lock screen /
 * Bluetooth / Android Auto — reconnects to a session with no active item,
 * e.g. after process death).
 *
 * Before baw.10 the service resolved [LastPlayed] against `accountManager.channels`
 * ONLY, so a paused library (Subsonic) track resumed to nothing or the last
 * radio station. This maps the persisted [LastPlayed] entry to a [Plan] the
 * service can act on directly — mirrors [PlaybackContract]'s pattern of keeping
 * the branch logic itself out of the Android-framework-coupled service class.
 */
object ResumptionPolicy {

    sealed interface Plan {
        data class ResumeChannel(val channel: Channel) : Plan
        data class ResumeLibraryTrack(
            val queue: List<Track>,
            val index: Int,
            val positionMs: Long,
            val albumTitle: String?,
        ) : Plan
        object NothingToResume : Plan
    }

    /**
     * @param channels the current channel pool to resolve a persisted channel id against.
     * @param hasSubsonicApi whether a Subsonic account/API is currently resolvable —
     *   without it a library track's stream URL can't be built, so resume is a no-op.
     */
    fun resolve(lastPlayed: LastPlayed?, channels: List<Channel>, hasSubsonicApi: Boolean): Plan {
        return when (lastPlayed) {
            null -> Plan.NothingToResume
            is LastPlayed.Channel -> {
                val channel = channels.find { it.id == lastPlayed.channelId }
                if (channel != null) Plan.ResumeChannel(channel) else Plan.NothingToResume
            }
            is LastPlayed.LibraryTrack -> {
                if (!hasSubsonicApi || lastPlayed.index !in lastPlayed.queue.indices) {
                    Plan.NothingToResume
                } else {
                    Plan.ResumeLibraryTrack(
                        queue = lastPlayed.queue,
                        index = lastPlayed.index,
                        positionMs = lastPlayed.positionMs,
                        albumTitle = lastPlayed.albumTitle,
                    )
                }
            }
        }
    }
}
