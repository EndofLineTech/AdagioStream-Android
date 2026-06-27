package com.adagiostream.android.service.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.account.AccountManager
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
    private val accountManager: AccountManager? = null,
    private val espnScoreService: ESPNScoreService? = null,
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
        // Observe VLC state changes + metadata updates and invalidate SimpleBasePlayer state
        scope.launch {
            combine(
                vlcWrapper.playbackState,
                vlcWrapper.currentChannel,
                accountManager?.trackMetadata ?: MutableStateFlow<Map<String, com.adagiostream.android.model.TrackMetadata>>(emptyMap()),
                espnScoreService?.gamesByChannel ?: MutableStateFlow<Map<String, com.adagiostream.android.model.ESPNGameInfo>>(emptyMap()),
                accountManager?.epgEntries ?: MutableStateFlow<Map<String, List<com.adagiostream.android.model.EPGEntry>>>(emptyMap()),
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

                    // Resolve dynamic artist text: SXM track > ESPN game > EPG program > channel name
                    val trackMeta = accountManager?.trackMetadata?.value?.get(channel.name)
                    val espnGame = espnScoreService?.gamesByChannel?.value?.get(channel.id)

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
                            trackMeta != null -> {
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

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
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
                    .build()
            )
            .setPlayWhenReady(currentPlayWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(currentPlaybackState)

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
                    SimpleBasePlayer.MediaItemData.Builder(ch.id.hashCode().toLong())
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
        } else {
            DebugLogger.log("getState() - no current media item, state=$currentPlaybackState", AUTO)
        }

        return builder.build()
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
        DebugLogger.log("handleSeek() - index=$mediaItemIndex, pos=$positionMs, command=$seekCommand, channelList=${vlcWrapper.channelList.size}", AUTO)
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

    override fun handleRelease(): ListenableFuture<*> {
        DebugLogger.log("handleRelease()", AUTO)
        vlcWrapper.release()
        return Futures.immediateVoidFuture()
    }
}
