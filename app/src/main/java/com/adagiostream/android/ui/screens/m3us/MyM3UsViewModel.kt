package com.adagiostream.android.ui.screens.m3us

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyM3UsViewModel @Inject constructor(
    private val playlistManager: CustomPlaylistManager,
    private val accountManager: AccountManager,
) : ViewModel() {

    val playlists: StateFlow<List<CustomPlaylist>> = playlistManager.playlists

    /** M3U provider accounts shown on this tab (matches iOS's consolidated Custom M3Us view). */
    val m3uAccounts: StateFlow<List<Account>> = accountManager.accounts
        .map { accounts -> accounts.filter { it.type is AccountType.M3U } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True only when BOTH custom playlists and M3U accounts are empty. */
    val isEmpty: StateFlow<Boolean> = combine(playlists, m3uAccounts) { p, a ->
        p.isEmpty() && a.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun createPlaylist(name: String) = playlistManager.createPlaylist(name)
    fun renamePlaylist(playlistId: String, name: String) = playlistManager.renamePlaylist(playlistId, name)
    fun deletePlaylist(playlistId: String) = playlistManager.deletePlaylist(playlistId)
    fun movePlaylists(from: Int, to: Int) = playlistManager.movePlaylists(from, to)

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            accountManager.deleteAccount(accountId)
        }
    }
}
