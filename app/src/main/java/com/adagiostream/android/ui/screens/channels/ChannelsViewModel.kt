package com.adagiostream.android.ui.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.ExoPlayerWrapper
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
    private val exoPlayer: ExoPlayerWrapper,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val isLoading: StateFlow<Boolean> = accountManager.isLoading
    val error: StateFlow<String?> = accountManager.error

    val filteredGroups: StateFlow<List<ChannelGroup>> = combine(
        accountManager.groups,
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
            accountManager.loadAllChannels()
        }
    }

    fun playChannel(channel: Channel) {
        val groupChannels = accountManager.groups.value
            .find { it.name == channel.group }
            ?.channels
            ?: emptyList()
        exoPlayer.setChannelList(groupChannels)
        exoPlayer.play(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            accountManager.toggleFavorite(channel)
        }
    }
}
