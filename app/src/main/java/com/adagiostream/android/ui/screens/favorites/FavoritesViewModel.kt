package com.adagiostream.android.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.VLCPlayerWrapper
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
    private val vlcPlayer: VLCPlayerWrapper,
) : ViewModel() {

    val feedMetadata: StateFlow<Map<String, TrackMetadata>> = accountManager.feedMetadata
    val espnGames: StateFlow<Map<String, ESPNGameInfo>> = accountManager.espnGames
    val epgEntries: StateFlow<Map<String, List<EPGEntry>>> = accountManager.epgEntries

    val favorites: StateFlow<List<Channel>> = accountManager.channels
        .map { channels ->
            channels
                .filter { it.isFavorite }
                .sortedBy { accountManager.favoriteIndexOf(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playChannel(channel: Channel) {
        vlcPlayer.setChannelList(favorites.value)
        vlcPlayer.play(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            accountManager.toggleFavorite(channel)
        }
    }

    fun onReorder(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            accountManager.reorderFavorites(fromIndex, toIndex)
        }
    }
}
