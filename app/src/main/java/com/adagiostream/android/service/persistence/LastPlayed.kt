package com.adagiostream.android.service.persistence

import com.adagiostream.android.service.navidrome.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identity of whatever was last playing, persisted so [onPlaybackResumption]
 * (lock screen / Bluetooth / Android Auto reconnecting to a killed process)
 * can rebuild playback (baw.10).
 *
 * Radio keeps the pre-existing shape (just a channel id). Library tracks
 * additionally carry the queue they were playing in + the active index +
 * elapsed position, so resume restarts the exact track at the exact spot
 * with next/prev still working — [Track] is already `@Serializable` and a
 * queue (one album's worth of tracks) is cheap to persist verbatim, so this
 * stores the queue rather than re-deriving it from a bare track id.
 */
@Serializable
sealed interface LastPlayed {

    @Serializable
    @SerialName("channel")
    data class Channel(val channelId: String) : LastPlayed

    @Serializable
    @SerialName("library_track")
    data class LibraryTrack(
        val queue: List<Track>,
        val index: Int,
        val positionMs: Long = 0L,
        val albumTitle: String? = null,
    ) : LastPlayed
}
