package com.adagiostream.android.service.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.provider.ProviderManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@UnstableApi
class VLCForwardingPlayer(
    private val vlcPlayer: VLCPlayerWrapper,
    private val providerManager: ProviderManager,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            combine(
                vlcPlayer.playbackState,
                vlcPlayer.currentChannel,
            ) { _, _ -> Unit }.collect {
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val vlcState = vlcPlayer.playbackState.value
        val channel = vlcPlayer.currentChannel.value

        val playbackState = when (vlcState) {
            is PlaybackState.Idle -> STATE_IDLE
            is PlaybackState.Buffering -> STATE_BUFFERING
            is PlaybackState.Playing -> STATE_READY
            is PlaybackState.Paused -> STATE_READY
            is PlaybackState.Error -> STATE_IDLE
        }

        val isPlaying = vlcState is PlaybackState.Playing

        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
                COMMAND_SET_MEDIA_ITEM,
            )
            .build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(vlcState is PlaybackState.Buffering)

        if (vlcState is PlaybackState.Error) {
            builder.setPlayerError(
                PlaybackException(
                    vlcState.message,
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
            )
        }

        if (channel != null) {
            val mediaItem = MediaItem.Builder()
                .setMediaId(channel.id)
                .setUri(channel.streamURL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(channel.name)
                        .setStation(channel.name)
                        .setArtist(channel.group)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .setIsPlayable(true)
                        .build()
                )
                .build()

            builder.setPlaylist(listOf(MediaItemData.Builder(channel.id)
                .setMediaItem(mediaItem)
                .setIsPlaceholder(false)
                .build()))
            builder.setCurrentMediaItemIndex(0)
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            vlcPlayer.resume()
        } else {
            vlcPlayer.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        vlcPlayer.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val mediaItem = mediaItems.firstOrNull() ?: return Futures.immediateVoidFuture()
        val mediaId = mediaItem.mediaId
        val channel = providerManager.channels.value.find { it.id == mediaId }
        if (channel != null) {
            vlcPlayer.play(channel)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT -> vlcPlayer.playNext()
            COMMAND_SEEK_TO_PREVIOUS -> vlcPlayer.playPrevious()
        }
        return Futures.immediateVoidFuture()
    }
}
