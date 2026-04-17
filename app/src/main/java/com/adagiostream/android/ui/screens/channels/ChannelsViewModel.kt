package com.adagiostream.android.ui.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val vlcPlayer: VLCPlayerWrapper,
    val playlistManager: CustomPlaylistManager,
) : ViewModel() {

    val customPlaylists: StateFlow<List<CustomPlaylist>> = playlistManager.playlists

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val isLoading: StateFlow<Boolean> = accountManager.isLoading
    val error: StateFlow<String?> = accountManager.error
    val feedMetadata: StateFlow<Map<String, TrackMetadata>> = accountManager.feedMetadata
    val espnGames: StateFlow<Map<String, ESPNGameInfo>> = accountManager.espnGames
    val epgEntries: StateFlow<Map<String, List<EPGEntry>>> = accountManager.epgEntries

    val filteredGroups: StateFlow<List<ChannelGroup>> = combine(
        accountManager.groups,
        playlistManager.playlists,
        accountManager.favoriteKeys,
        _searchQuery,
    ) { groups, playlists, favKeys, query ->
        val customGroups = playlists.flatMap { playlist ->
            playlist.groups.mapNotNull { group ->
                val channels = group.entries.map { entry ->
                    val ch = entry.asChannel().copy(group = group.name)
                    ch.copy(isFavorite = accountManager.favoriteKey(ch) in favKeys)
                }
                if (channels.isNotEmpty()) ChannelGroup(name = group.name, channels = channels) else null
            }
        }
        val allGroups = groups + customGroups
        if (query.isBlank()) {
            allGroups
        } else {
            allGroups.mapNotNull { group ->
                val filtered = group.channels.filter {
                    it.name.contains(query, ignoreCase = true)
                }
                if (filtered.isNotEmpty()) group.copy(channels = filtered) else null
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            accountManager.loadAllChannels()
        }
    }

    fun playChannel(channel: Channel) {
        val groupChannels = accountManager.groups.value
            .find { it.name == channel.group }
            ?.channels
            ?: playlistManager.playlists.value
                .flatMap { it.groups }
                .find { it.name == channel.group }
                ?.entries
                ?.map { it.asChannel().copy(group = channel.group) }
            ?: emptyList()
        vlcPlayer.setChannelList(groupChannels)
        vlcPlayer.play(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            accountManager.toggleFavorite(channel)
        }
    }

    fun toggleGroupFavorite(groupName: String) {
        viewModelScope.launch {
            accountManager.toggleGroupFavorite(groupName)
        }
    }

    fun hideGroup(groupName: String) {
        viewModelScope.launch {
            if (accountManager.isGroupEnabled(groupName)) {
                accountManager.toggleGroupEnabled(groupName)
            }
        }
    }

    fun isGroupFavorite(groupName: String): Boolean {
        return accountManager.isGroupFavorite(groupName)
    }

    fun loadEPGForChannel(channel: Channel) {
        viewModelScope.launch {
            accountManager.loadXtreamEPGForChannel(channel)
        }
    }
}
