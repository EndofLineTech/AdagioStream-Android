package com.adagiostream.android.service.player

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class ExoPlayerWrapper(
    context: Context,
    initialBufferSeconds: Int = 10,
    private val okHttpClient: OkHttpClient? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0f)
    val bitrateKbps: StateFlow<Float> = _bitrateKbps.asStateFlow()

    /** Epoch millis when the current stream started, null when idle. */
    private val _streamStartedAt = MutableStateFlow<Long?>(null)
    val streamStartedAt: StateFlow<Long?> = _streamStartedAt.asStateFlow()

    private var channelList: List<Channel> = emptyList()
    private var retryCount = 0
    private var reconnectCount = 0
    private val maxReconnects = 5
    private var retryResetJob: Job? = null
    private var stallDetectorJob: Job? = null

    var bufferDurationSeconds: Int = initialBufferSeconds
        private set
    private var builtBufferSeconds: Int = initialBufferSeconds

    /** Incremented each time the underlying ExoPlayer is rebuilt (e.g. buffer setting change). */
    var playerVersion: Int = 0
        private set

    private var context: Context = context

    val player: Player get() = exoPlayer

    @OptIn(UnstableApi::class)
    private var exoPlayer: ExoPlayer = buildPlayer(context)

    fun updateBufferDuration(seconds: Int) {
        bufferDurationSeconds = seconds.coerceIn(5, 15)
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context): ExoPlayer {
        val bufferMs = bufferDurationSeconds * 1000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMs * 2,            // min buffer — 2x setting for headroom
                bufferMs * 4,            // max buffer — 4x setting for long playback stability
                bufferMs / 2,            // buffer for playback start
                bufferMs,                // buffer after rebuffer — matches user setting
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)

        if (okHttpClient != null) {
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("AdagioStream/1.0-Android")
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory))
        }

        return builder
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && _currentChannel.value != null) {
                            reconnectCount++
                            if (reconnectCount > maxReconnects) {
                                _playbackState.value = PlaybackState.Error("Stream ended — could not reconnect")
                                return
                            }
                            val delayMs = (1000L * reconnectCount).coerceAtMost(5_000L)
                            scope.launch {
                                delay(delayMs)
                                exoPlayer.seekToDefaultPosition()
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                            return
                        }
                        scope.launch { syncPlaybackState() }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            reconnectCount = 0
                            retryResetJob?.cancel()
                            retryResetJob = scope.launch {
                                delay(30_000L)
                                retryCount = 0
                            }
                            startStallDetector()
                        } else {
                            stallDetectorJob?.cancel()
                        }
                        scope.launch { syncPlaybackState() }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        scope.launch {
                            retryResetJob?.cancel()

                            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                                exoPlayer.seekToDefaultPosition()
                                exoPlayer.prepare()
                                exoPlayer.play()
                                return@launch
                            }

                            val isNetworkError = error.errorCode in
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED

                            if (isNetworkError) {
                                retryCount++
                                val delayMs = (2000L * retryCount).coerceAtMost(30_000L)
                                _playbackState.value = PlaybackState.Buffering
                                delay(delayMs)
                                exoPlayer.prepare()
                                exoPlayer.play()
                            } else {
                                _playbackState.value = PlaybackState.Error(
                                    UrlSanitizer.redact(error.message ?: "Playback failed")
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

    /**
     * Detects silent playback stalls caused by audio sink errors (e.g. PTS timestamp
     * discontinuities) that don't propagate to onPlayerError. Checks every 3 seconds
     * whether currentPosition is advancing; if stalled for 6+ seconds, re-prepares.
     */
    private fun startStallDetector() {
        stallDetectorJob?.cancel()
        stallDetectorJob = scope.launch {
            var lastPosition = exoPlayer.currentPosition
            var stallCount = 0
            while (true) {
                delay(3_000L)
                if (!exoPlayer.isPlaying) break
                val pos = exoPlayer.currentPosition
                if (pos == lastPosition) {
                    stallCount++
                    if (stallCount >= 2) {
                        // Position hasn't moved for ~6 seconds while isPlaying — stalled
                        stallCount = 0
                        _playbackState.value = PlaybackState.Buffering
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                        exoPlayer.play()
                        break // onIsPlayingChanged will restart the detector
                    }
                } else {
                    stallCount = 0
                }
                lastPosition = pos
            }
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

    @OptIn(UnstableApi::class)
    private fun rebuildPlayerIfNeeded() {
        if (bufferDurationSeconds != builtBufferSeconds) {
            exoPlayer.stop()
            exoPlayer.release()
            exoPlayer = buildPlayer(context)
            builtBufferSeconds = bufferDurationSeconds
            playerVersion++
        }
    }

    fun play(channel: Channel) {
        rebuildPlayerIfNeeded()

        _currentChannel.value = channel
        _playbackState.value = PlaybackState.Buffering
        _bitrateKbps.value = 0f
        _streamStartedAt.value = System.currentTimeMillis()
        retryCount = 0
        reconnectCount = 0
        retryResetJob?.cancel()

        ContextCompat.startForegroundService(
            context, Intent(context, AudioPlaybackService::class.java)
        )

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
        stallDetectorJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        retryResetJob?.cancel()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
        _streamStartedAt.value = null
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
        stallDetectorJob?.cancel()
        retryResetJob?.cancel()
        exoPlayer.release()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
    }
}
