package com.adagiostream.android.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.service.player.MusicPlaybackCoordinator
import com.adagiostream.android.service.player.PlaybackSource
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
    private val musicPlaybackCoordinator: MusicPlaybackCoordinator,
) : ViewModel() {

    /** What the player is currently rendering — radio [Channel] or on-demand [PlaybackSource.Library]. */
    val playbackSource: StateFlow<PlaybackSource?> = vlcPlayer.playbackSource

    /**
     * True while a library (on-demand) track is playing (baw.9.3 / baw.9.5).
     * Drives whether "Up Next" and the positional seek bar are shown — both are
     * library-only surfaces; radio keeps its existing live-only controls.
     */
    val isLibrarySource: StateFlow<Boolean> = playbackSource
        .map { it is PlaybackSource.Library }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** The current library queue, or empty when radio is playing / nothing queued (baw.9.3). */
    val libraryQueue: StateFlow<List<Track>> = playbackSource
        .map { (it as? PlaybackSource.Library)?.queue ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Index of the active track within [libraryQueue], or -1 when not applicable (baw.9.3). */
    val libraryQueueIndex: StateFlow<Int> = playbackSource
        .map { (it as? PlaybackSource.Library)?.index ?: -1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    /** Jumps directly to [index] in the library queue (Up Next tap-to-jump; baw.9.3). */
    fun playQueueIndex(index: Int) = musicPlaybackCoordinator.playIndex(index)

    /** Reorders the library queue — see [MusicPlaybackCoordinator.moveQueueItem] (baw.9.3). */
    fun moveQueueItem(from: Int, to: Int) = musicPlaybackCoordinator.moveQueueItem(from, to)

    /**
     * Whether shuffle is on (baw.16). The Up Next sheet shows the CANONICAL
     * queue, so drag-to-reorder is hidden while shuffling — canonical positions
     * don't map onto the shuffle play order (see [MusicPlaybackCoordinator.moveQueueItem]).
     * Tap-to-jump stays canonical in both modes.
     */
    val isShuffleEnabled: StateFlow<Boolean> = musicPlaybackCoordinator.shuffleEnabledFlow

    /** The active library track, or null when not playing library content (baw.9.3). */
    val currentLibraryTrack: StateFlow<Track?> = playbackSource
        .map { (it as? PlaybackSource.Library)?.currentTrack }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Authenticated cover-art URL for the current library track (baw.9.3). */
    val libraryArtworkUrl: StateFlow<String?> = musicPlaybackCoordinator.nowPlayingArtworkUrl

    /** Album title for the current library session, if known (baw.9.3). */
    val libraryAlbumTitle: StateFlow<String?> = musicPlaybackCoordinator.nowPlayingAlbumTitle

    /**
     * Skips to the next / previous library track (baw.9.3).
     *
     * Distinct from [playNext] / [playPrevious], which drive RADIO channel
     * navigation via [VLCPlayerWrapper] — library navigation is owned by
     * [MusicPlaybackCoordinator] instead, so these route there directly rather
     * than risk the radio-only methods silently no-oping.
     */
    fun libraryNext() = musicPlaybackCoordinator.next()
    fun libraryPrevious() = musicPlaybackCoordinator.previous()

    /**
     * Duration of the current library track in milliseconds, or null when not
     * playing library content / duration is unknown (baw.9.5). Radio is never
     * seekable and has no duration — see [PlaybackContract].
     */
    val libraryDurationMs: StateFlow<Long?> = playbackSource
        .map { source ->
            (source as? PlaybackSource.Library)?.currentTrack?.duration
                ?.takeIf { it > 0 }
                ?.let { it * 1000L }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Current playback position in milliseconds — a plain (non-Flow) read of
     * the live libVLC position, polled by the UI (baw.9.5). Only meaningful
     * while [isLibrarySource] is true; radio has no seekable position.
     */
    fun currentPositionMs(): Long = vlcPlayer.currentPositionMs()

    /** Seeks the current library track to [positionMs] (baw.9.5). No-op for radio. */
    fun seekTo(positionMs: Long) = vlcPlayer.seekToPositionMs(positionMs)

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
        accountManager.espnGames,
        accountManager.appSettings,
    ) { channel, metadata, games, settings ->
        if (channel == null) return@combine null
        val track = metadata[channel.name] ?: return@combine null
        // beads_adagio-59p.3.3: with "prefer live scores" on, an in-progress
        // ESPN game on this channel outranks the SXM track. Suppressing the
        // track here lets every consumer (sheet, mini player) fall through to
        // its existing ESPN branch.
        val game = games[channel.id]
        if (settings.preferLiveScoresOverMetadata && game?.state == ESPNGameInfo.GameState.LIVE) {
            null
        } else {
            track
        }
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
