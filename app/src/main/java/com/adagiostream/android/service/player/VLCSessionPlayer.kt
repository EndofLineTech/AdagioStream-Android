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

    init {
        DebugLogger.log("VLCSessionPlayer init", AUTO)
        // Observe VLC state changes and invalidate SimpleBasePlayer state
        scope.launch {
            combine(
                vlcWrapper.playbackState,
                vlcWrapper.currentChannel,
            ) { state, channel -> state to channel }.collect { (state, channel) ->
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

                if (prevState != currentPlaybackState) {
                    DebugLogger.log("VLCSessionPlayer state: $state → media3State=$currentPlaybackState, playWhenReady=$currentPlayWhenReady", AUTO)
                }

                if (channel != null) {
                    // Resolve dynamic artist text: SXM track > ESPN game > group name
                    val trackMeta = accountManager?.trackMetadata?.value?.get(channel.name)
                    val espnGame = espnScoreService?.gamesByChannel?.value?.get(channel.id)

                    val displayTitle: String
                    val displayArtist: String
                    val artworkUri: android.net.Uri?

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
                        else -> {
                            displayTitle = channel.name
                            displayArtist = channel.group
                            artworkUri = channel.logoURL?.let { android.net.Uri.parse(it) }
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
        if (item != null) {
            val meta = item.mediaMetadata
            DebugLogger.log("getState() - mediaId=${item.mediaId}, title=${meta.title}, artist=${meta.artist}, station=${meta.station}, artworkUri=${meta.artworkUri}", AUTO)
            builder.setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder(item.mediaId.hashCode().toLong())
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .build()))
            builder.setCurrentMediaItemIndex(0)
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
        DebugLogger.log("handleSeek() - index=$mediaItemIndex, command=$seekCommand", AUTO)
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> vlcWrapper.playNext()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> vlcWrapper.playPrevious()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        DebugLogger.log("handleRelease()", AUTO)
        vlcWrapper.release()
        return Futures.immediateVoidFuture()
    }
}
