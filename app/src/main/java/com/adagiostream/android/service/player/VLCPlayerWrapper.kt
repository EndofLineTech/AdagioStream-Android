package com.adagiostream.android.service.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class VLCPlayerWrapper(
    private val context: Context,
    initialBufferSeconds: Int = 10,
) {
    companion object {
        private const val TAG = "AdagioPlayer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = kotlinx.coroutines.flow.MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: kotlinx.coroutines.flow.StateFlow<PlaybackState> = _playbackState

    private val _currentChannel = kotlinx.coroutines.flow.MutableStateFlow<Channel?>(null)
    val currentChannel: kotlinx.coroutines.flow.StateFlow<Channel?> = _currentChannel

    private val _bitrateKbps = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val bitrateKbps: kotlinx.coroutines.flow.StateFlow<Float> = _bitrateKbps

    private val _streamStartedAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val streamStartedAt: kotlinx.coroutines.flow.StateFlow<Long?> = _streamStartedAt

    private var channelList: List<Channel> = emptyList()

    var bufferDurationSeconds: Int = initialBufferSeconds
        private set

    private val libVLC: LibVLC = buildLibVLC()
    private var mediaPlayer: MediaPlayer = MediaPlayer(libVLC)
    private var bitrateJob: Job? = null
    private var peakBitrateKbps: Float = 0f
    private var isSwitchingChannels: Boolean = false

    init {
        attachEventListener()
    }

    private fun buildLibVLC(): LibVLC {
        val cachingMs = (bufferDurationSeconds * 1000).toString()
        val options = arrayListOf(
            "--aout=opensles",
            "--audio-resampler=soxr",
            "--no-video",
            "--network-caching=$cachingMs",
            "--live-caching=$cachingMs",
            "--file-caching=$cachingMs",
            "-vv",  // verbose logging for debugging; reduce to -v or remove later
        )
        return LibVLC(context, options)
    }

    private fun attachEventListener() {
        mediaPlayer.setEventListener { event ->
            scope.launch {
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                isSwitchingChannels = false
                Log.i(TAG, "VLC: Opening stream for ${_currentChannel.value?.name}")
                _playbackState.value = PlaybackState.Buffering
            }
            MediaPlayer.Event.Buffering -> {
                val pct = event.buffering
                Log.d(TAG, "VLC: Buffering ${pct}%")
                if (pct < 100f) {
                    _playbackState.value = PlaybackState.Buffering
                }
            }
            MediaPlayer.Event.Playing -> {
                Log.i(TAG, "VLC: Playing ${_currentChannel.value?.name}")
                _playbackState.value = PlaybackState.Playing
                startBitratePolling()
            }
            MediaPlayer.Event.Paused -> {
                Log.i(TAG, "VLC: Paused")
                _playbackState.value = PlaybackState.Paused
                stopBitratePolling()
            }
            MediaPlayer.Event.Stopped -> {
                Log.i(TAG, "VLC: Stopped")
                stopBitratePolling()
            }
            MediaPlayer.Event.EndReached -> {
                // Ignore stale events from channel switches or user-initiated stop
                if (!isSwitchingChannels && _currentChannel.value != null) {
                    Log.w(TAG, "VLC: EndReached — stream ended for ${_currentChannel.value?.name}")
                    _playbackState.value = PlaybackState.Error("Stream ended")
                    stopBitratePolling()
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                if (!isSwitchingChannels && _currentChannel.value != null) {
                    Log.e(TAG, "VLC: EncounteredError for ${_currentChannel.value?.name}")
                    _playbackState.value = PlaybackState.Error("Playback error")
                    stopBitratePolling()
                }
            }
        }
    }

    private fun startBitratePolling() {
        bitrateJob?.cancel()
        bitrateJob = scope.launch {
            while (true) {
                delay(1_000L)
                val media = mediaPlayer.media ?: continue
                val stats = media.stats ?: continue
                // VLC reports bitrate in KB/s; convert to kbps (× 8)
                val inputKbps = stats.inputBitrate * 8f
                val demuxKbps = stats.demuxBitrate * 8f
                val currentKbps = maxOf(inputKbps, demuxKbps)
                if (currentKbps > peakBitrateKbps) {
                    peakBitrateKbps = currentKbps
                }
                _bitrateKbps.value = peakBitrateKbps
            }
        }
    }

    private fun stopBitratePolling() {
        bitrateJob?.cancel()
        bitrateJob = null
    }

    fun updateBufferDuration(seconds: Int) {
        bufferDurationSeconds = seconds.coerceIn(5, 15)
        // Buffer setting takes effect on next play() — no player rebuild needed with VLC
    }

    fun setChannelList(channels: List<Channel>) {
        channelList = channels
    }

    fun play(channel: Channel) {
        Log.i(TAG, "play(): channel=${channel.name}, url=${UrlSanitizer.redact(channel.streamURL)}")

        // Guard against stale EndReached/Error events from the old stream —
        // VLC dispatches events asynchronously so they arrive after stop() returns.
        isSwitchingChannels = true
        mediaPlayer.stop()

        _currentChannel.value = channel
        _playbackState.value = PlaybackState.Buffering
        _bitrateKbps.value = 0f
        peakBitrateKbps = 0f
        _streamStartedAt.value = System.currentTimeMillis()

        ContextCompat.startForegroundService(
            context, Intent(context, AudioPlaybackService::class.java)
        )

        val cachingMs = (bufferDurationSeconds * 1000).toString()
        val media = Media(libVLC, Uri.parse(channel.streamURL))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=$cachingMs")
        media.addOption(":live-caching=$cachingMs")
        media.addOption(":no-video")

        mediaPlayer.media = media
        media.release() // MediaPlayer holds its own reference
        mediaPlayer.play()
    }

    fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
    }

    fun resume() {
        mediaPlayer.play()
    }

    fun togglePlayPause() {
        when (_playbackState.value) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume()
            else -> _currentChannel.value?.let { play(it) }
        }
    }

    fun stop() {
        Log.i(TAG, "stop(): channel=${_currentChannel.value?.name}")
        _currentChannel.value = null  // Clear before stop so async events are ignored
        mediaPlayer.stop()
        stopBitratePolling()
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

    /** Whether the VLC player is currently playing audio. */
    val isPlaying: Boolean get() = mediaPlayer.isPlaying

    fun release() {
        stopBitratePolling()
        mediaPlayer.release()
        libVLC.release()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
    }
}
