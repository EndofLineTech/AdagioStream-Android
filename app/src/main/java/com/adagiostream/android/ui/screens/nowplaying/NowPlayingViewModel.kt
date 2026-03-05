package com.adagiostream.android.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.VLCPlayerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val vlcPlayer: VLCPlayerWrapper,
    private val accountManager: AccountManager,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = vlcPlayer.playbackState
    val currentChannel: StateFlow<Channel?> = vlcPlayer.currentChannel
    val bitrateKbps: StateFlow<Float> = vlcPlayer.bitrateKbps
    val streamStartedAt: StateFlow<Long?> = vlcPlayer.streamStartedAt

    val currentEPGEntries: StateFlow<List<EPGEntry>> = combine(
        vlcPlayer.currentChannel,
        accountManager.epgEntries,
    ) { channel, epgMap ->
        if (channel == null) return@combine emptyList()
        val channelId = channel.epgChannelID ?: return@combine emptyList()
        epgMap[channelId] ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            vlcPlayer.currentChannel.collect { channel ->
                if (channel?.xtreamStreamId != null) {
                    accountManager.loadXtreamEPGForChannel(channel)
                }
            }
        }
    }

    fun togglePlayPause() = vlcPlayer.togglePlayPause()
    fun stop() = vlcPlayer.stop()
    fun playNext() = vlcPlayer.playNext()
    fun playPrevious() = vlcPlayer.playPrevious()
}
