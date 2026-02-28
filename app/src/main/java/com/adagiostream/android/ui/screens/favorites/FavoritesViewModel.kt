package com.adagiostream.android.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.ExoPlayerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val exoPlayer: ExoPlayerWrapper,
) : ViewModel() {

    val favorites: StateFlow<List<Channel>> = accountManager.channels
        .map { channels -> channels.filter { it.isFavorite }.sortedBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playChannel(channel: Channel) {
        exoPlayer.setChannelList(accountManager.channels.value.filter { it.isFavorite })
        exoPlayer.play(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            accountManager.toggleFavorite(channel)
        }
    }
}
