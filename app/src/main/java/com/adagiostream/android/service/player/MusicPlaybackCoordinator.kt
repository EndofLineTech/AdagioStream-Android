package com.adagiostream.android.service.player

import com.adagiostream.android.service.download.DownloadLocator
import com.adagiostream.android.service.download.LocalFirstResolver
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates Navidrome library playback: bridges the canonical [MusicQueueManager]
 * to the audio player, resolving authenticated stream + cover-art URLs from the
 * active [NavidromeApi] (baw.3.2 / baw.3.3 / baw.3.5).
 *
 * This is the testable heart of the E3 engine. It owns every non-trivial library
 * decision — start an album from a tapped track, auto-advance on track-end
 * (honouring [RepeatMode.One]), manual next/previous, shuffle/repeat toggles —
 * and drives playback through the [LibraryTrackPlayer] port, so all of it is unit
 * tested on the JVM with a fake player and a real (network-free) [NavidromeApi]
 * (only its pure URL builders are used here).
 *
 * The [api] is supplied per play request (it is account-derived and not a
 * singleton); it is retained so subsequent auto-advance / next / previous can keep
 * building stream URLs for the same library session.
 */
@Singleton
class MusicPlaybackCoordinator @Inject constructor(
    private val queue: MusicQueueManager,
    private val player: LibraryTrackPlayer,
    /**
     * Local-first lookup (baw.6.3): when a track is fully downloaded, the player
     * loads its local file instead of the stream URL. Defaults to [DownloadLocator.None]
     * so the JVM coordinator tests (and the no-download path) keep streaming.
     */
    private val downloadLocator: DownloadLocator = DownloadLocator.None,
) {

    /** The [NavidromeApi] backing the current library session; null until [playAlbum]. */
    private var api: NavidromeApi? = null

    /**
     * Coroutine scope for fire-and-forget operations such as scrobbling (baw.5.1).
     *
     * Exposed as `internal` so unit tests can inject a [kotlinx.coroutines.test.TestScope]
     * with [kotlinx.coroutines.test.UnconfinedTestDispatcher] to run launched
     * coroutines eagerly and verify scrobble calls synchronously.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guard that ensures the scrobble submission fires at most once per track (baw.5.1). */
    private var scrobbleSubmitted = false

    private val _nowPlayingArtworkUrl = MutableStateFlow<String?>(null)
    private val _nowPlayingAlbumTitle = MutableStateFlow<String?>(null)

    /** Album title for the current library session, surfaced as MediaSession album metadata (baw.3.4). */
    val nowPlayingAlbumTitle: StateFlow<String?> = _nowPlayingAlbumTitle.asStateFlow()

    /** Whether the underlying queue is shuffling — exposed through the session (baw.3.5). */
    val shuffleEnabled: Boolean get() = queue.shuffleEnabled

    /** The underlying queue's repeat mode — exposed through the session (baw.3.5). */
    val repeatMode: RepeatMode get() = queue.repeatMode

    /**
     * Authenticated cover-art URL for the currently-playing library track, or null
     * when nothing library-sourced is playing / the track has no cover art.
     *
     * This is the resolved value behind [NowPlayingItem.artworkUrl] for tracks —
     * the model can't reach the API, so the authenticated URL is resolved here and
     * read by the session player when building MediaSession metadata (baw.3.4).
     */
    val nowPlayingArtworkUrl: StateFlow<String?> = _nowPlayingArtworkUrl.asStateFlow()

    /**
     * Replaces the queue with [tracks], starts playback from [startIndex], and
     * remembers [api] for the rest of this library session (PO decision: tapping a
     * track enqueues the WHOLE album and starts from the tapped index — baw.3.8).
     */
    fun playAlbum(tracks: List<Track>, startIndex: Int, api: NavidromeApi, albumTitle: String? = null) {
        this.api = api
        _nowPlayingAlbumTitle.value = albumTitle
        queue.setQueue(tracks, startIndex)
        playCurrent()
    }

    /**
     * Handles a natural track-end (libVLC EndReached → AutoAdvance). Repeat-one
     * restarts the current track; otherwise advances, stopping when the queue is
     * exhausted with no repeat-all wrap (baw.3.3).
     */
    fun onTrackEnded() {
        when (autoAdvanceAction(queue.repeatMode)) {
            AutoAdvanceAction.RestartCurrent -> playCurrent()
            AutoAdvanceAction.AdvanceToNext -> advanceOrStop()
        }
    }

    /** User-initiated skip to the next track (lock-screen / notification / UI). */
    fun next() {
        if (queue.isEmpty) return
        advanceOrStop()
    }

    /** User-initiated skip to the previous track (restarts current at queue start). */
    fun previous() {
        if (queue.isEmpty) return
        queue.previous()
        playCurrent()
    }

    /**
     * Jumps straight to [index] in the current queue and plays it — backs the
     * lock-screen / system-UI "tap a queue item" action (COMMAND_SEEK_TO_MEDIA_ITEM).
     * No-op when [index] is out of range.
     */
    fun playIndex(index: Int) {
        val tracks = queue.queue
        if (index !in tracks.indices) return
        queue.setQueue(tracks, index)
        playCurrent()
    }

    /** Enables/disables shuffle on the underlying queue (baw.3.5). */
    fun setShuffle(enabled: Boolean) = queue.setShuffle(enabled)

    /** Sets the repeat mode on the underlying queue (baw.3.5). */
    fun setRepeatMode(mode: RepeatMode) {
        queue.repeatMode = mode
    }

    /** The currently-active library track, or null when nothing is queued. */
    fun currentTrack(): Track? = queue.current()

    /**
     * Reports playback progress for the current library track (baw.5.1).
     *
     * When [LibraryPlaybackPolicy.shouldSubmit] returns `true` AND the scrobble
     * guard has not yet fired for the current track, fires a scrobble submission
     * (submission=true) exactly once per track, fire-and-forget.
     *
     * No-op when no library session is active (api == null) — this ensures the
     * IPTV/Radio paths never trigger a scrobble.
     *
     * @param elapsedSeconds  Seconds of playback elapsed so far.
     */
    fun reportPlaybackProgress(elapsedSeconds: Long) {
        if (scrobbleSubmitted) return
        val api = api ?: return
        val track = queue.current() ?: return
        val durationSeconds = track.duration?.toLong()
        if (LibraryPlaybackPolicy.shouldSubmit(elapsedSeconds, durationSeconds)) {
            scrobbleSubmitted = true
            scope.launch {
                api.scrobble(track.id, submission = true)
            }
        }
    }

    private fun advanceOrStop() {
        val next = queue.next()
        if (next != null) {
            playCurrent()
        } else {
            _nowPlayingArtworkUrl.value = null
            _nowPlayingAlbumTitle.value = null
            player.stop()
        }
    }

    private fun playCurrent() {
        val track = queue.current() ?: return
        val api = api ?: return
        // Local-first (baw.6.3): a downloaded track plays from disk; otherwise stream.
        val localPath = downloadLocator.localPathFor(track.id)
        val streamUrl = LocalFirstResolver.resolve(
            localPath = localPath,
            streamUrl = api.streamUrl(track.id)?.toString(),
        ) ?: return
        _nowPlayingArtworkUrl.value = track.coverArt?.let { api.getCoverArtUrl(it)?.toString() }
        // Reset per-track scrobble guard and fire now-playing notification (baw.5.1).
        scrobbleSubmitted = false
        scope.launch {
            api.scrobble(track.id, submission = false)
        }
        player.playLibraryTrack(
            streamUrl = streamUrl,
            source = PlaybackSource.Library(queue.queue, queue.currentIndex),
        )
    }
}
