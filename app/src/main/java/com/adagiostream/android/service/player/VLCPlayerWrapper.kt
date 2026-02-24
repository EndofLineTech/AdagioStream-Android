package com.adagiostream.android.service.player

import android.content.Context
import android.net.Uri
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class VLCPlayerWrapper(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var statsJob: Job? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0f)
    val bitrateKbps: StateFlow<Float> = _bitrateKbps.asStateFlow()

    private var channelList: List<Channel> = emptyList()

    var bufferDurationSeconds: Int = 10

    fun setChannelList(channels: List<Channel>) {
        channelList = channels
    }

    fun play(channel: Channel) {
        stop()

        _currentChannel.value = channel
        _playbackState.value = PlaybackState.Buffering

        try {
            val vlcOptions = arrayListOf(
                "--aout=opensles",
                "--audio-time-stretch",
                "--no-video",
                "--network-caching=${bufferDurationSeconds * 1000}",
                "--live-caching=${bufferDurationSeconds * 1000}",
            )

            if (libVLC == null) {
                libVLC = LibVLC(context, vlcOptions)
            }

            val player = MediaPlayer(libVLC!!)
            mediaPlayer = player

            player.setEventListener { event ->
                scope.launch {
                    handleEvent(event)
                }
            }

            val media = Media(libVLC!!, Uri.parse(channel.streamURL))
            media.setHWDecoderEnabled(false, false)
            player.media = media
            media.release()

            player.play()
            startStatsPolling()
        } catch (e: Exception) {
            _playbackState.value = PlaybackState.Error(e.message ?: "Playback failed")
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _playbackState.value = PlaybackState.Paused
            }
        }
    }

    fun resume() {
        mediaPlayer?.let {
            it.play()
            _playbackState.value = PlaybackState.Playing
        }
    }

    fun togglePlayPause() {
        when (_playbackState.value) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume()
            else -> _currentChannel.value?.let { play(it) }
        }
    }

    fun stop() {
        statsJob?.cancel()
        statsJob = null

        mediaPlayer?.let {
            it.setEventListener(null)
            it.stop()
            it.release()
        }
        mediaPlayer = null

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

    fun release() {
        stop()
        libVLC?.release()
        libVLC = null
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> _playbackState.value = PlaybackState.Playing
            MediaPlayer.Event.Paused -> _playbackState.value = PlaybackState.Paused
            MediaPlayer.Event.Stopped -> _playbackState.value = PlaybackState.Idle
            MediaPlayer.Event.EncounteredError -> {
                _playbackState.value = PlaybackState.Error("Playback error")
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering < 100f) {
                    _playbackState.value = PlaybackState.Buffering
                }
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                try {
                    val stats = mediaPlayer?.media?.stats
                    if (stats != null) {
                        _bitrateKbps.value = stats.demuxBitrate * 8f
                    }
                } catch (_: Exception) {
                    // Stats unavailable
                }
            }
        }
    }
}
