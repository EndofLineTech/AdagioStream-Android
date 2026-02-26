package com.adagiostream.android.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.player.ExoPlayerWrapper
import com.adagiostream.android.service.provider.ProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val providerManager: ProviderManager,
    private val exoPlayer: ExoPlayerWrapper,
) : ViewModel() {

    val favorites: StateFlow<List<Channel>> = providerManager.channels
        .map { channels -> channels.filter { it.isFavorite }.sortedBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playChannel(channel: Channel) {
        exoPlayer.setChannelList(providerManager.channels.value)
        exoPlayer.play(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            providerManager.toggleFavorite(channel)
        }
    }
}
