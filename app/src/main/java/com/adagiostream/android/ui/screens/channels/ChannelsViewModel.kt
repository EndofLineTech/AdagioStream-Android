package com.adagiostream.android.ui.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.service.provider.ProviderManager
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
    private val providerManager: ProviderManager,
    private val vlcPlayer: VLCPlayerWrapper,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val isLoading: StateFlow<Boolean> = providerManager.isLoading
    val error: StateFlow<String?> = providerManager.error

    val filteredGroups: StateFlow<List<ChannelGroup>> = combine(
        providerManager.groups,
        _searchQuery,
    ) { groups, query ->
        if (query.isBlank()) {
            groups
        } else {
            groups.mapNotNull { group ->
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
            providerManager.loadAllChannels()
        }
    }

    fun playChannel(channel: Channel) {
        vlcPlayer.setChannelList(providerManager.channels.value)
        vlcPlayer.play(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            providerManager.toggleFavorite(channel)
        }
    }
}
