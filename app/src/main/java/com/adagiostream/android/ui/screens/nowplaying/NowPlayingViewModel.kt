package com.adagiostream.android.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.player.VLCPlayerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val vlcPlayer: VLCPlayerWrapper,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = vlcPlayer.playbackState
    val currentChannel: StateFlow<Channel?> = vlcPlayer.currentChannel
    val bitrateKbps: StateFlow<Float> = vlcPlayer.bitrateKbps

    fun togglePlayPause() = vlcPlayer.togglePlayPause()
    fun stop() = vlcPlayer.stop()
    fun playNext() = vlcPlayer.playNext()
    fun playPrevious() = vlcPlayer.playPrevious()
}
