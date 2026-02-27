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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExoPlayerWrapper(context: Context, initialBufferSeconds: Int = 10) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0f)
    val bitrateKbps: StateFlow<Float> = _bitrateKbps.asStateFlow()

    private var channelList: List<Channel> = emptyList()
    private var retryCount = 0
    private val maxRetries = 5

    var bufferDurationSeconds: Int = initialBufferSeconds

    private var context: Context = context

    val player: Player get() = exoPlayer

    @OptIn(UnstableApi::class)
    private var exoPlayer: ExoPlayer = buildPlayer(context)

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context): ExoPlayer {
        val bufferMs = bufferDurationSeconds * 1000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMs,                // min buffer
                bufferMs * 2,            // max buffer
                bufferMs / 2,            // buffer for playback start
                bufferMs,                // buffer for playback after rebuffer
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        scope.launch { syncPlaybackState() }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) retryCount = 0
                        scope.launch { syncPlaybackState() }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        scope.launch {
                            val isSourceError = error.errorCode in
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                            if (isSourceError && retryCount < maxRetries) {
                                retryCount++
                                val delayMs = (1000L * retryCount).coerceAtMost(5000L)
                                _playbackState.value = PlaybackState.Buffering
                                delay(delayMs)
                                exoPlayer.prepare()
                                exoPlayer.play()
                            } else {
                                _playbackState.value = PlaybackState.Error(
                                    error.message ?: "Playback failed"
                                )
                            }
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
