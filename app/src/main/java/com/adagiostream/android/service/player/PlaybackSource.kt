package com.adagiostream.android.service.player

import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.navidrome.Track

// ============================================================================
// PlaybackSource + NowPlayingItem — port of iOS Models/PlaybackSource.swift
// (baw.3.7 — DESIGN GATE for the Navidrome E3 queue engine).
//
// The player can play two fundamentally different things:
//   - live radio / IPTV channels (non-seekable, no duration, never "ends")
//   - on-demand library tracks   (seekable, real duration, auto-advance on end)
//
// `PlaybackSource` is the single discriminator the player branches on, and
// `NowPlayingItem` is the unifying surface that lets VLCSessionPlayer.getState()
// build now-playing metadata for either kind through one code path.
//
// CONTRACT (see PlaybackContract for the machine-readable policy):
//   Radio   → isSeekable=false, durationUs=TIME_UNSET, isDynamic=true,
//             EndReached treated as a transient error → retry (live streams
//             must never legitimately end). THIS IS THE EXISTING LIVE BEHAVIOUR
//             AND MUST NOT CHANGE.
//   Library → isSeekable=true, durationUs=real, isDynamic=false,
//             EndReached is a legitimate track-completion signal → auto-advance.
// ============================================================================

/**
 * A single item the player can present in the now-playing / mini-player surface.
 *
 * Both live radio ([Channel]) and on-demand library ([Track]) are projected onto
 * this interface via adapters so the UI and the media session expose them through
 * one surface regardless of playback mode.
 */
interface NowPlayingItem {
    /** Primary display text (channel name or track title). */
    val displayTitle: String

    /** Secondary display text, if available (channel group or artist name). */
    val displaySubtitle: String?

    /**
     * Optional artwork URL for the item.
     *
     * For [Track] this stays `null`: a Subsonic cover-art URL requires an
     * authenticated base URL the model does not own, so the real authenticated URL
     * is resolved one layer up by `MusicPlaybackCoordinator.nowPlayingArtworkUrl`
     * (baw.3.2/3.4) and read by the session player when building MediaSession
     * metadata. For [Channel] it is the channel logo.
     */
    val artworkUrl: String?

    /**
     * True for live-stream items that cannot be seeked and never legitimately
     * end. Drives the player's seek/duration/end-of-stream contract.
     */
    val isLiveStream: Boolean
}

/** Projects a live [Channel] onto the [NowPlayingItem] surface. */
fun Channel.asNowPlayingItem(): NowPlayingItem = ChannelNowPlayingItem(this)

/** Projects an on-demand library [Track] onto the [NowPlayingItem] surface. */
fun Track.asNowPlayingItem(): NowPlayingItem = TrackNowPlayingItem(this)

private data class ChannelNowPlayingItem(val channel: Channel) : NowPlayingItem {
    override val displayTitle: String get() = channel.name
    override val displaySubtitle: String? get() = channel.group.ifEmpty { null }
    override val artworkUrl: String? get() = channel.logoURL
    override val isLiveStream: Boolean get() = true
}

private data class TrackNowPlayingItem(val track: Track) : NowPlayingItem {
    override val displayTitle: String get() = track.title

    /**
     * The denormalised artist display name ([Track.artist]); never the opaque
     * `artistId`, which must not surface in the UI. Null when absent (e.g. rows
     * cached before the column existed, or blank).
     */
    override val displaySubtitle: String? get() = track.artist?.ifEmpty { null }

    /**
     * Always null here — the authenticated cover-art URL is resolved by
     * `MusicPlaybackCoordinator.nowPlayingArtworkUrl`, which has the API context
     * the model lacks (baw.3.2/3.4).
     */
    override val artworkUrl: String? get() = null

    override val isLiveStream: Boolean get() = false
}

/**
 * Describes what the player is currently playing.
 *
 * This is the chokepoint the whole E3 epic branches on. The E3 foundation
 * (baw.3.7) defines both variants and wires the branch; only [Radio] is ever
 * assigned by the live path today — [Library] is constructed once baw.3.2
 * builds stream URLs and starts a track.
 */
sealed interface PlaybackSource {
    /** The item currently presented by this source. */
    val currentItem: NowPlayingItem

    /** Live radio / IPTV channel stream. */
    data class Radio(val channel: Channel) : PlaybackSource {
        override val currentItem: NowPlayingItem get() = channel.asNowPlayingItem()
    }

    /**
     * On-demand library queue. [index] is the index of the active track within
     * [queue]. The canonical queue is never mutated; navigation/shuffle ordering
     * lives in MusicQueueManager (baw.3.1), which produces these snapshots.
     */
    data class Library(val queue: List<Track>, val index: Int) : PlaybackSource {
        /** The active track. Throws if [index] is out of bounds — callers must keep it valid. */
        val currentTrack: Track get() = queue[index]

        override val currentItem: NowPlayingItem get() = currentTrack.asNowPlayingItem()
    }

    /**
     * An Audiobookshelf book on its global timeline (beads_adagio-59p.1.5).
     *
     * One snapshot per metadata state — the AudiobookPlaybackCoordinator swaps
     * in a fresh copy on chapter change (via `updateAudiobookSource`) so the
     * media session subtitle follows the current chapter. Seek/duration are
     * BOOK-GLOBAL: [totalDurationSeconds] is the whole book, and positional
     * seeks route through the coordinator's timeline, never straight into the
     * single file libVLC happens to have loaded.
     */
    data class Audiobook(
        val libraryItemId: String,
        val bookTitle: String,
        val author: String?,
        /** Current chapter title on the global timeline, if the book has chapters. */
        val chapterTitle: String?,
        val totalDurationSeconds: Double,
        val coverUrl: String?,
    ) : PlaybackSource {
        override val currentItem: NowPlayingItem get() = AudiobookNowPlayingItem(this)

        /**
         * Redacts [coverUrl] — it carries a `?token=` JWT (beads_adagio-59p.1.7
         * E1). The default data-class toString would leak it into every log
         * site that interpolates a source (e.g. VLCSessionPlayer.handleSeek).
         */
        override fun toString(): String =
            "Audiobook(libraryItemId=$libraryItemId, bookTitle=$bookTitle, chapterTitle=$chapterTitle)"
    }
}

private data class AudiobookNowPlayingItem(val book: PlaybackSource.Audiobook) : NowPlayingItem {
    override val displayTitle: String get() = book.bookTitle
    override val displaySubtitle: String? get() = book.chapterTitle ?: book.author
    override val artworkUrl: String? get() = book.coverUrl
    override val isLiveStream: Boolean get() = false
}
