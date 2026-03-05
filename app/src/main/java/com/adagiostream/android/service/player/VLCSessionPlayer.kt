package com.adagiostream.android.service.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.adagiostream.android.model.PlaybackState
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
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentMediaItem: MediaItem? = null
    private var currentPlaybackState: Int = STATE_IDLE
    private var currentPlayWhenReady: Boolean = false

    init {
        // Observe VLC state changes and invalidate SimpleBasePlayer state
        scope.launch {
            combine(
                vlcWrapper.playbackState,
                vlcWrapper.currentChannel,
            ) { state, channel -> state to channel }.collect { (state, channel) ->
                currentPlaybackState = when (state) {
                    is PlaybackState.Idle -> STATE_IDLE
                    is PlaybackState.Buffering -> STATE_BUFFERING
                    is PlaybackState.Playing -> STATE_READY
                    is PlaybackState.Paused -> STATE_READY
                    is PlaybackState.CatchingUp -> STATE_READY
                    is PlaybackState.Error -> STATE_IDLE
                }
                currentPlayWhenReady = state is PlaybackState.Playing || state is PlaybackState.Buffering || state is PlaybackState.CatchingUp

                if (channel != null) {
                    currentMediaItem = MediaItem.Builder()
                        .setMediaId(channel.id)
                        .setUri(channel.streamURL)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(channel.name)
                                .setStation(channel.name)
                                .setArtist(channel.group)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                .setIsPlayable(true)
                                .apply { channel.logoURL?.let { setArtworkUri(android.net.Uri.parse(it)) } }
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
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_METADATA,
                    )
                    .build()
            )
            .setPlayWhenReady(currentPlayWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(currentPlaybackState)

        val item = currentMediaItem
        if (item != null) {
            builder.setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder(item.mediaId.hashCode().toLong())
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .build()))
            builder.setCurrentMediaItemIndex(0)
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
        if (playWhenReady) {
            vlcWrapper.resume()
        } else {
            vlcWrapper.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        vlcWrapper.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT -> vlcWrapper.playNext()
            COMMAND_SEEK_TO_PREVIOUS -> vlcWrapper.playPrevious()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        vlcWrapper.release()
        return Futures.immediateVoidFuture()
    }
}
