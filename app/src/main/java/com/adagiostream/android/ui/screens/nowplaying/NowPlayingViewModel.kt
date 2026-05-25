package com.adagiostream.android.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.service.player.VLCPlayerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val vlcPlayer: VLCPlayerWrapper,
    private val accountManager: AccountManager,
    private val castManager: CastManager,
) : ViewModel() {

    val isCasting: StateFlow<Boolean> = castManager.isCasting
    val castDeviceName: StateFlow<String?> = castManager.castDeviceName

    val playbackState: StateFlow<PlaybackState> = vlcPlayer.playbackState
    val currentChannel: StateFlow<Channel?> = vlcPlayer.currentChannel
    val bitrateKbps: StateFlow<Float> = vlcPlayer.bitrateKbps
    val streamStartedAt: StateFlow<Long?> = vlcPlayer.streamStartedAt

    val currentESPNGame: StateFlow<ESPNGameInfo?> = combine(
        vlcPlayer.currentChannel,
        accountManager.espnGames,
    ) { channel, games ->
        if (channel == null) null else games[channel.id]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentEPGEntries: StateFlow<List<EPGEntry>> = combine(
        vlcPlayer.currentChannel,
        accountManager.epgEntries,
    ) { channel, epgMap ->
        if (channel == null) return@combine emptyList()
        val channelId = channel.epgChannelID ?: return@combine emptyList()
        epgMap[channelId] ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isFavorite: StateFlow<Boolean> = combine(
        vlcPlayer.currentChannel,
        accountManager.channels,
    ) { current, channels ->
        if (current == null) return@combine false
        channels.any { it.streamURL == current.streamURL && it.isFavorite }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val currentTrackMetadata: StateFlow<TrackMetadata?> = combine(
        vlcPlayer.currentChannel,
        accountManager.trackMetadata,
    ) { channel, metadata ->
        if (channel == null) return@combine null
        metadata[channel.name]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isTrackLoved: StateFlow<Boolean> = combine(
        vlcPlayer.currentChannel,
        accountManager.trackMetadata,
        accountManager.lovedTracks,
    ) { channel, metadata, lovedTracks ->
        if (channel == null) return@combine false
        val track = metadata[channel.name] ?: return@combine false
        lovedTracks.any { it.artist == track.artist && it.title == track.title }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            vlcPlayer.currentChannel.collect { channel ->
                if (channel != null) {
                    if (channel.xtreamStreamId != null) {
                        accountManager.loadXtreamEPGForChannel(channel)
                    }
                    accountManager.startTrackMetadataPolling(channel)
                } else {
                    accountManager.stopTrackMetadataPolling()
                }
            }
        }
    }

    val isTimeShifted: StateFlow<Boolean> = vlcPlayer.timeShiftMs
        .map { it > 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val timeShiftMs: StateFlow<Long> = vlcPlayer.timeShiftMs
    val listeningTimeMs: StateFlow<Long> = vlcPlayer.listeningTimeMs

    fun togglePlayPause() = vlcPlayer.togglePlayPause()
    fun stop() = vlcPlayer.stop()
    fun playNext() = vlcPlayer.playNext()
    fun playPrevious() = vlcPlayer.playPrevious()
    fun seekToLive() = vlcPlayer.seekToLive()

    fun toggleFavorite() {
        val channel = vlcPlayer.currentChannel.value ?: return
        viewModelScope.launch {
            accountManager.toggleFavorite(channel)
        }
    }

    fun toggleLovedTrack() {
        val channel = vlcPlayer.currentChannel.value ?: return
        val track = accountManager.trackMetadata.value[channel.name] ?: return
        viewModelScope.launch {
            accountManager.toggleLovedTrack(track, channel.name)
        }
    }
}
