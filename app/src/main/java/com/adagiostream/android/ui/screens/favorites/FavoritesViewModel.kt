package com.adagiostream.android.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.player.VLCPlayerWrapper
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
    private val vlcPlayer: VLCPlayerWrapper,
) : ViewModel() {

    val favorites: StateFlow<List<Channel>> = providerManager.channels
        .map { channels -> channels.filter { it.isFavorite }.sortedBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
