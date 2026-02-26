package com.adagiostream.android.service.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExoPlayerWrapper(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0f)
    val bitrateKbps: StateFlow<Float> = _bitrateKbps.asStateFlow()

    private var channelList: List<Channel> = emptyList()

    var bufferDurationSeconds: Int = 10
        set(value) {
            field = value
            rebuildPlayer(context)
        }

    private var context: Context = context

    val player: Player get() = exoPlayer

    @OptIn(UnstableApi::class)
    private var exoPlayer: ExoPlayer = buildPlayer(context)

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferDurationSeconds * 1000,
                bufferDurationSeconds * 2 * 1000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        scope.launch { syncPlaybackState() }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        scope.launch { syncPlaybackState() }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        scope.launch {
                            _playbackState.value = PlaybackState.Error(
                                error.message ?: "Playback failed"
                            )
                        }
                    }
                })

                player.addAnalyticsListener(@OptIn(UnstableApi::class) object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: androidx.media3.common.Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?,
                    ) {
                        scope.launch {
                            _bitrateKbps.value = if (format.bitrate != androidx.media3.common.Format.NO_VALUE) {
                                format.bitrate / 1000f
                            } else {
                                0f
                            }
                        }
                    }
                })
            }
    }

    @OptIn(UnstableApi::class)
    private fun rebuildPlayer(context: Context) {
        val wasPlaying = _currentChannel.value
        exoPlayer.stop()
        exoPlayer.release()
        exoPlayer = buildPlayer(context)
        if (wasPlaying != null) {
            play(wasPlaying)
        }
    }

    private fun syncPlaybackState() {
        val state = when (exoPlayer.playbackState) {
            Player.STATE_IDLE -> PlaybackState.Idle
            Player.STATE_BUFFERING -> PlaybackState.Buffering
            Player.STATE_READY -> {
                if (exoPlayer.isPlaying) PlaybackState.Playing else PlaybackState.Paused
            }
            Player.STATE_ENDED -> PlaybackState.Idle
            else -> PlaybackState.Idle
        }
        _playbackState.value = state
    }

    fun setChannelList(channels: List<Channel>) {
        channelList = channels
    }

    fun play(channel: Channel) {
        _currentChannel.value = channel
        _playbackState.value = PlaybackState.Buffering
        _bitrateKbps.value = 0f

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

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        exoPlayer.play()
    }

    fun togglePlayPause() {
        when (_playbackState.value) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume()
            else -> _currentChannel.value?.let { play(it) }
        }
    }

    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
    }

    fun playNext() {
        val current = _currentChannel.value ?: return
        val index = channelList.indexOfFirst { it.streamURL == current.streamURL }
        if (index >= 0 && index < channelList.size - 1) {
            play(channelList[index + 1])
        }
    }

    fun playPrevious() {
        val current = _currentChannel.value ?: return
        val index = channelList.indexOfFirst { it.streamURL == current.streamURL }
        if (index > 0) {
            play(channelList[index - 1])
        }
    }

    fun prepareFromMediaId(channel: Channel) {
        setChannelList(listOf(channel))
        play(channel)
    }

    fun release() {
        exoPlayer.release()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
    }
}
