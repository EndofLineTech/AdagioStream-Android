package com.adagiostream.android.service.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackParameters
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.audiobookshelf.AudiobookPlaybackCoordinator
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.DebugLogger.Category.AUTO
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bridges VLCPlayerWrapper to Media3's Player interface via SimpleBasePlayer.
 * This allows MediaLibrarySession to work with libVLC for Android Auto,
 * lock screen controls, and media notifications.
 */
@OptIn(UnstableApi::class)
class VLCSessionPlayer(
    private val vlcWrapper: VLCPlayerWrapper,
    private val coordinator: MusicPlaybackCoordinator? = null,
    private val accountManager: AccountManager? = null,
    private val espnScoreService: ESPNScoreService? = null,
    private val audiobookCoordinator: AudiobookPlaybackCoordinator? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentMediaItem: MediaItem? = null
    private var currentPlaybackState: Int = STATE_IDLE
    private var currentPlayWhenReady: Boolean = false
    private var playbackStartTimeMs: Long = 0L

    // Channel announcement: briefly show channel name on switch before showing track info
    private var announcingChannelId: String? = null
    private var announceJob: kotlinx.coroutines.Job? = null
    private companion object {
        const val CHANNEL_ANNOUNCE_MS = 2000L
    }

    init {
        DebugLogger.log("VLCSessionPlayer init", AUTO)
        // Library playback (baw.3.4): refresh session state whenever the active
        // source changes — a new queue track, or a radio↔library switch — so the
        // lock screen / notification track metadata follows auto-advance & next/prev.
        scope.launch {
            vlcWrapper.playbackSource.collect { invalidateState() }
        }
        // Observe VLC state changes + metadata updates and invalidate SimpleBasePlayer state
        scope.launch {
            combine(
                vlcWrapper.playbackState,
                vlcWrapper.currentChannel,
                accountManager?.trackMetadata ?: MutableStateFlow<Map<String, com.adagiostream.android.model.TrackMetadata>>(emptyMap()),
                espnScoreService?.gamesByChannel ?: MutableStateFlow<Map<String, com.adagiostream.android.model.ESPNGameInfo>>(emptyMap()),
                accountManager?.epgEntries ?: MutableStateFlow<Map<String, List<com.adagiostream.android.model.EPGEntry>>>(emptyMap()),
                // Recompose when the prefer-live-scores toggle flips (beads_adagio-59p.3.3)
                accountManager?.appSettings ?: MutableStateFlow(com.adagiostream.android.model.AppSettings()),
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                (values[0] as PlaybackState) to (values[1] as com.adagiostream.android.model.Channel?)
            }.collect { (state, channel) ->
                val prevState = currentPlaybackState
                currentPlaybackState = when (state) {
                    is PlaybackState.Idle -> STATE_IDLE
                    is PlaybackState.Buffering -> STATE_BUFFERING
                    is PlaybackState.Playing -> STATE_READY
                    is PlaybackState.Paused -> STATE_READY
                    is PlaybackState.CatchingUp -> STATE_READY
                    is PlaybackState.Error -> STATE_IDLE
                }
                currentPlayWhenReady = state is PlaybackState.Playing || state is PlaybackState.Buffering || state is PlaybackState.CatchingUp

                // Track when playback started so we can report stable elapsed time
                if (currentPlaybackState == STATE_READY && prevState != STATE_READY) {
                    playbackStartTimeMs = System.currentTimeMillis()
                } else if (currentPlaybackState == STATE_IDLE) {
                    playbackStartTimeMs = 0L
                }

                if (prevState != currentPlaybackState) {
                    DebugLogger.log("VLCSessionPlayer state: $state → media3State=$currentPlaybackState, playWhenReady=$currentPlayWhenReady", AUTO)
                }

                if (channel != null) {
                    // Detect channel switch → announce channel name briefly
                    val prevChannelId = currentMediaItem?.mediaId
                    if (prevChannelId != null && prevChannelId != channel.id) {
                        announcingChannelId = channel.id
                        announceJob?.cancel()
                        announceJob = scope.launch {
                            kotlinx.coroutines.delay(CHANNEL_ANNOUNCE_MS)
                            announcingChannelId = null
                            invalidateState()
                        }
                    }

                    // Resolve dynamic artist text: SXM track > ESPN game > EPG program > channel name.
                    // During time-shift catch-up, show the track that was actually playing at the
                    // buffered position (now - timeShiftMs) instead of the live now-playing track.
                    val trackMeta = if (state is PlaybackState.CatchingUp) {
                        val atMillis = System.currentTimeMillis() - vlcWrapper.timeShiftMs.value
                        accountManager?.historicalTrackMetadata(channel, atMillis)
                            ?: accountManager?.trackMetadata?.value?.get(channel.name)
                    } else {
                        accountManager?.trackMetadata?.value?.get(channel.name)
                    }
                    val espnGame = espnScoreService?.gamesByChannel?.value?.get(channel.id)
                    // beads_adagio-59p.3.3: an in-progress game outranks the SXM
                    // track when the user prefers live scores (default on).
                    val liveGameWins = espnGame?.state == com.adagiostream.android.model.ESPNGameInfo.GameState.LIVE &&
                        accountManager?.appSettings?.value?.preferLiveScoresOverMetadata != false

                    val displayTitle: String
                    val displayArtist: String
                    val artworkUri: android.net.Uri?

                    // During channel announcement, show channel name/group instead of track info
                    if (announcingChannelId == channel.id) {
                        displayTitle = channel.name
                        displayArtist = channel.group
                        artworkUri = channel.logoURL?.let { android.net.Uri.parse(it) }
                    } else {
                        val epgEntry = channel.epgChannelID?.let { epgId ->
                            accountManager?.epgEntries?.value?.get(epgId)?.firstOrNull { it.isCurrentlyAiring }
                        }
                        when {
                            trackMeta != null && !liveGameWins -> {
                                displayTitle = trackMeta.title
                                displayArtist = trackMeta.artist
                                artworkUri = (trackMeta.albumArtURL ?: channel.logoURL)?.let { android.net.Uri.parse(it) }
                            }
                            espnGame != null -> {
                                displayTitle = espnGame.nowPlayingTitle
                                displayArtist = espnGame.nowPlayingSubtitle
                                artworkUri = channel.logoURL?.let { android.net.Uri.parse(it) }
                            }
                            epgEntry != null -> {
                                displayTitle = epgEntry.title
                                displayArtist = channel.name
                                artworkUri = channel.logoURL?.let { android.net.Uri.parse(it) }
                            }
                            else -> {
                                displayTitle = channel.name
                                displayArtist = channel.group
                                artworkUri = channel.logoURL?.let { android.net.Uri.parse(it) }
                            }
                        }
                    }

                    currentMediaItem = MediaItem.Builder()
                        .setMediaId(channel.id)
                        .setUri(channel.streamURL)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(displayTitle)
                                .setStation(channel.name)
                                .setArtist(displayArtist)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                .setIsPlayable(true)
                                .apply { artworkUri?.let { setArtworkUri(it) } }
                                .build()
                        )
                        .build()
                } else {
                    currentMediaItem = null
                }

                invalidateState()
            }
        }
    }

    /**
     * SimpleBasePlayer requires every MediaItemData UID in a playlist to be
     * unique, but Channel/Track ids can collide (GH#9: two channels sharing a
     * tvg-id crash with "Duplicate MediaItemData UID"). Mixing the slot index
     * into the high bits guarantees uniqueness; seeks are index-based, so UIDs
     * are only used internally by Media3 for item identity.
     */
    private fun slotUid(index: Int, id: String): Long =
        (index.toLong() shl 32) or (id.hashCode().toLong() and 0xFFFFFFFFL)

    override fun getState(): State {
        val source = vlcWrapper.playbackSource.value
        val builder = State.Builder()
            .setAvailableCommands(availableCommands(source))
            .setPlayWhenReady(currentPlayWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(currentPlaybackState)

        val hasPlaylist = when (source) {
            is PlaybackSource.Library -> buildLibraryState(builder, source)
            is PlaybackSource.Audiobook -> buildAudiobookState(builder, source)
            else -> buildRadioState(builder)
        }
        // SimpleBasePlayer forbids an empty playlist outside IDLE/ENDED. A
        // session command (e.g. media-button pause) can arrive while the
        // source has no content but VLC still reports READY/BUFFERING —
        // coerce to IDLE instead of crashing.
        if (!hasPlaylist && currentPlaybackState != STATE_IDLE && currentPlaybackState != STATE_ENDED) {
            builder.setPlaybackState(STATE_IDLE)
        }
        return builder.build()
    }

    /**
     * Command set per source. Radio keeps EXACTLY the live command set (no
     * positional seek, no shuffle/repeat) — that's the live regression guard.
     * Library adds positional seek + shuffle/repeat so the scrubber and mode
     * toggles work for music tracks (baw.3.5 / baw.3.6).
     */
    private fun availableCommands(source: PlaybackSource?): Player.Commands {
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_PREPARE,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
            )
        if (source is PlaybackSource.Library) {
            commands.addAll(
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
            )
        }
        // Audiobooks (beads_adagio-59p.1.5): whole-book positional seek +
        // variable speed. Next/prev stay in the base set — they route to
        // chapter skip in handleSeek. No shuffle/repeat for books.
        if (source is PlaybackSource.Audiobook) {
            commands.addAll(
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_SET_SPEED_AND_PITCH,
            )
        }
        return commands.build()
    }

    /**
     * Session state for audiobook playback (beads_adagio-59p.1.5): one media
     * item — title = book title, artist/subtitle = current chapter (falling
     * back to the author) — with the WHOLE BOOK's duration and the global
     * timeline position from the coordinator, plus the active playback speed.
     */
    private fun buildAudiobookState(builder: State.Builder, source: PlaybackSource.Audiobook): Boolean {
        val artworkUri = source.coverUrl?.let { android.net.Uri.parse(it) }
        val metadata = MediaMetadata.Builder()
            .setTitle(source.bookTitle)
            .setArtist(source.chapterTitle ?: source.author)
            .setSubtitle(source.chapterTitle)
            .setAlbumTitle(source.bookTitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { artworkUri?.let { setArtworkUri(it) } }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(source.libraryItemId)
            .setMediaMetadata(metadata)
            .build()
        builder.setPlaylist(
            listOf(
                SimpleBasePlayer.MediaItemData.Builder(source.libraryItemId.hashCode().toLong())
                    .setMediaItem(mediaItem)
                    .setMediaMetadata(metadata)
                    .setIsPlaceholder(false)
                    .setDefaultPositionUs(0)
                    .setDurationUs(PlaybackContract.durationUs(source))
                    .setIsSeekable(PlaybackContract.isSeekable(source))
                    .setIsDynamic(PlaybackContract.isDynamic(source))
                    .build()
            )
        )
        builder.setCurrentMediaItemIndex(0)
        builder.setContentPositionMs(audiobookCoordinator?.globalPositionMs() ?: 0L)
        builder.setPlaybackParameters(PlaybackParameters(audiobookCoordinator?.speed ?: 1.0f))
        builder.setIsLoading(currentPlaybackState == STATE_BUFFERING)
        return true
    }

    /**
     * Builds session state for an on-demand library queue (baw.3.4 / 3.5 / 3.6):
     * per-track title/artist(NAME)/album/artwork, a real seekable duration +
     * position, and shuffle/repeat state — all sourced from [PlaybackContract] and
     * the [coordinator] so the live contract stays untouched.
     */
    private fun buildLibraryState(builder: State.Builder, source: PlaybackSource.Library): Boolean {
        val tracks = source.queue
        if (tracks.isEmpty()) return false
        val currentIndex = source.index.coerceIn(0, tracks.lastIndex)
        val artworkUri = coordinator?.nowPlayingArtworkUrl?.value?.let { android.net.Uri.parse(it) }
        val albumTitle = coordinator?.nowPlayingAlbumTitle?.value

        val playlistItems = tracks.mapIndexed { index, track ->
            val isCurrent = index == currentIndex
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                // Artist display NAME (Track.artist), never the opaque artistId.
                .setArtist(track.artist)
                .setAlbumTitle(albumTitle)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true)
                .setIsBrowsable(false)
            if (isCurrent && artworkUri != null) metaBuilder.setArtworkUri(artworkUri)
            val mediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(metaBuilder.build())
                .build()
            // Only the active track carries the real source so duration resolves;
            // others are placeholders so Media3 exposes next/prev without prefetch.
            val itemSource = if (isCurrent) source else null
            SimpleBasePlayer.MediaItemData.Builder(slotUid(index, track.id))
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setIsPlaceholder(!isCurrent)
                .setDefaultPositionUs(0)
                .setDurationUs(if (isCurrent) PlaybackContract.durationUs(source) else C.TIME_UNSET)
                .setIsSeekable(PlaybackContract.isSeekable(itemSource))
                .setIsDynamic(PlaybackContract.isDynamic(source))
                .build()
        }
        builder.setPlaylist(playlistItems)
        builder.setCurrentMediaItemIndex(currentIndex)
        // Real scrubber position/duration come from libVLC via the wrapper (baw.3.6).
        builder.setContentPositionMs(vlcWrapper.currentPositionMs())
        builder.setIsLoading(currentPlaybackState == STATE_BUFFERING)
        // Shuffle + repeat surfaced through the session (baw.3.5).
        builder.setShuffleModeEnabled(coordinator?.shuffleEnabled ?: false)
        builder.setRepeatMode(
            Media3RepeatMapping.toMedia3(coordinator?.repeatMode ?: RepeatMode.Off),
        )
        return true
    }

    /** Builds session state for the live radio path — unchanged live behaviour. */
    private fun buildRadioState(builder: State.Builder): Boolean {
        val item = currentMediaItem
        val currentChannel = vlcWrapper.currentChannel.value
        if (item != null && currentChannel != null) {
            // If currentMediaItem is stale (e.g. combine flow hasn't caught up), use the
            // actual current channel for the active slot so AA never shows wrong metadata.
            val activeItem = if (item.mediaId == currentChannel.id) item else {
                DebugLogger.log("getState() - STALE currentMediaItem: item.mediaId=${item.mediaId} vs currentChannel.id=${currentChannel.id} (${currentChannel.name}), rebuilding", AUTO)
                MediaItem.Builder()
                    .setMediaId(currentChannel.id)
                    .setUri(currentChannel.streamURL)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(currentChannel.name)
                            .setStation(currentChannel.name)
                            .setArtist(currentChannel.group)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                            .setIsPlayable(true)
                            .apply { currentChannel.logoURL?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                            .build()
                    )
                    .build()
            }
            val meta = activeItem.mediaMetadata
            DebugLogger.log("getState() - mediaId=${activeItem.mediaId}, title=${meta.title}, artist=${meta.artist}, station=${meta.station}, artworkUri=${meta.artworkUri}", AUTO)

            // Per-source playback contract (baw.3.7). This block only runs for the
            // live path (gated on currentChannel != null), so the source is Radio
            // (or null) and these resolve to the existing live values —
            // isSeekable=false, durationUs=TIME_UNSET, isDynamic=true. Reading them
            // from PlaybackContract keeps the live MediaItemData locked to a single
            // source of truth that PlaybackContractTest guards.
            val source = vlcWrapper.playbackSource.value
            val liveSeekable = PlaybackContract.isSeekable(source)
            val liveDurationUs = PlaybackContract.durationUs(source)
            val liveDynamic = PlaybackContract.isDynamic(source)

            // Build playlist from channel list so Media3 exposes skip next/previous actions
            val channels = vlcWrapper.channelList
            if (channels.size > 1) {
                val currentIndex = channels.indexOfFirst { it.id == currentChannel.id }.coerceAtLeast(0)
                val playlistItems = channels.mapIndexed { index, ch ->
                    val mediaItem = if (ch.id == currentChannel.id) activeItem else {
                        MediaItem.Builder()
                            .setMediaId(ch.id)
                            .setUri(ch.streamURL)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(ch.name)
                                    .setStation(ch.name)
                                    .setArtist(ch.group)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                    }
                    SimpleBasePlayer.MediaItemData.Builder(slotUid(index, ch.id))
                        .setMediaItem(mediaItem)
                        .setMediaMetadata(mediaItem.mediaMetadata)
                        .setIsPlaceholder(index != currentIndex)
                        .setDefaultPositionUs(0)
                        .setDurationUs(liveDurationUs)
                        .setIsSeekable(liveSeekable)
                        .setIsDynamic(liveDynamic)
                        .build()
                }
                builder.setPlaylist(playlistItems)
                builder.setCurrentMediaItemIndex(currentIndex)
            } else {
                builder.setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder(activeItem.mediaId.hashCode().toLong())
                    .setMediaItem(activeItem)
                    .setMediaMetadata(activeItem.mediaMetadata)
                    .setIsPlaceholder(false)
                    .setDefaultPositionUs(0)
                    .setDurationUs(liveDurationUs)
                    .setIsSeekable(liveSeekable)
                    .setIsDynamic(liveDynamic)
                    .build()))
                builder.setCurrentMediaItemIndex(0)
            }

            // Report stable elapsed time so the clock doesn't reset on every invalidateState()
            val elapsedMs = if (playbackStartTimeMs > 0) System.currentTimeMillis() - playbackStartTimeMs else 0L
            builder.setContentPositionMs(elapsedMs)
            builder.setIsLoading(currentPlaybackState == STATE_BUFFERING)
            return true
        } else {
            DebugLogger.log("getState() - no current media item, state=$currentPlaybackState", AUTO)
            return false
        }
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // Playback is handled by VLCPlayerWrapper.play() via the service callback
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        DebugLogger.log("handleSetPlayWhenReady($playWhenReady)", AUTO)
        if (playWhenReady) {
            vlcWrapper.resume()
        } else {
            vlcWrapper.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        DebugLogger.log("handleStop()", AUTO)
        vlcWrapper.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        DebugLogger.log("handleSeek() - index=$mediaItemIndex, pos=$positionMs, command=$seekCommand, source=${vlcWrapper.playbackSource.value}", AUTO)

        // Audiobook (beads_adagio-59p.1.5): next/prev = chapter skip (with the
        // >3s restart rule in the coordinator's timeline); positional seek is
        // BOOK-GLOBAL and crosses file boundaries via the coordinator.
        if (vlcWrapper.playbackSource.value is PlaybackSource.Audiobook) {
            when (seekCommand) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> audiobookCoordinator?.nextChapter()
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> audiobookCoordinator?.previousChapter()
                else -> {
                    DebugLogger.log("handleSeek() - audiobook global seek to ${positionMs}ms", AUTO)
                    audiobookCoordinator?.seekTo(positionMs / 1000.0)
                }
            }
            return Futures.immediateVoidFuture()
        }

        // Library: next/prev drive the queue; positional seek scrubs the track (baw.3.6).
        // Radio path is unchanged — channel next/prev only, never positional.
        if (vlcWrapper.playbackSource.value is PlaybackSource.Library) {
            when (seekCommand) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> coordinator?.next()
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> coordinator?.previous()
                COMMAND_SEEK_TO_MEDIA_ITEM -> {
                    DebugLogger.log("handleSeek() - library jump to queue index $mediaItemIndex", AUTO)
                    if (mediaItemIndex != C.INDEX_UNSET) coordinator?.playIndex(mediaItemIndex)
                }
                else -> {
                    DebugLogger.log("handleSeek() - library positional seek to ${positionMs}ms", AUTO)
                    vlcWrapper.seekToPositionMs(positionMs)
                }
            }
            return Futures.immediateVoidFuture()
        }

        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                DebugLogger.log("handleSeek() - skipping to next", AUTO)
                vlcWrapper.playNext()
            }
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                DebugLogger.log("handleSeek() - skipping to previous", AUTO)
                vlcWrapper.playPrevious()
            }
            else -> {
                // Media3 may send COMMAND_SEEK_TO_MEDIA_ITEM with a specific index
                val channels = vlcWrapper.channelList
                if (mediaItemIndex in channels.indices) {
                    DebugLogger.log("handleSeek() - seeking to channel index $mediaItemIndex: ${channels[mediaItemIndex].name}", AUTO)
                    vlcWrapper.play(channels[mediaItemIndex])
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        DebugLogger.log("handleSetPlaybackParameters(speed=${playbackParameters.speed})", AUTO)
        // Variable speed is an audiobook-only feature (beads_adagio-59p.1.5).
        if (vlcWrapper.playbackSource.value is PlaybackSource.Audiobook) {
            audiobookCoordinator?.setSpeed(playbackParameters.speed)
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        DebugLogger.log("handleSetRepeatMode($repeatMode)", AUTO)
        coordinator?.setRepeatMode(Media3RepeatMapping.fromMedia3(repeatMode))
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        DebugLogger.log("handleSetShuffleModeEnabled($shuffleModeEnabled)", AUTO)
        coordinator?.setShuffle(shuffleModeEnabled)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        DebugLogger.log("handleRelease()", AUTO)
        vlcWrapper.release()
        return Futures.immediateVoidFuture()
    }
}
