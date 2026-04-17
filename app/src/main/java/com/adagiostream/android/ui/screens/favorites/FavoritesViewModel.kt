package com.adagiostream.android.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val vlcPlayer: VLCPlayerWrapper,
    private val playlistManager: CustomPlaylistManager,
) : ViewModel() {

    val feedMetadata: StateFlow<Map<String, TrackMetadata>> = accountManager.feedMetadata
    val espnGames: StateFlow<Map<String, ESPNGameInfo>> = accountManager.espnGames
    val epgEntries: StateFlow<Map<String, List<EPGEntry>>> = accountManager.epgEntries

    val favorites: StateFlow<List<Channel>> = combine(
        accountManager.channels,
        playlistManager.playlists,
        accountManager.favoriteKeys,
    ) { channels, playlists, favKeys ->
        val accountFavs = channels
            .filter { it.isFavorite }

        val customFavs = playlists.flatMap { playlist ->
            playlist.groups.flatMap { group ->
                group.entries.map { entry ->
                    val ch = entry.asChannel().copy(group = group.name)
                    ch.copy(isFavorite = true)
                }.filter { accountManager.favoriteKey(it) in favKeys }
            }
        }

        // Deduplicate by favorite key (account channels take priority)
        val seenKeys = accountFavs.map { accountManager.favoriteKey(it) }.toSet()
        val dedupedCustom = customFavs.filter { accountManager.favoriteKey(it) !in seenKeys }

        (accountFavs + dedupedCustom)
            .sortedBy { accountManager.favoriteIndexOf(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
