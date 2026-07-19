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
import com.adagiostream.android.service.persistence.PersistenceService
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

/**
 * Sentinel expand-key for the Favorites section (beads_adagio-59p.4.1, iOS
 * parity: `ChannelListView.favoritesKey`). The leading NUL can't appear in an
 * M3U group-title, so a real group named "Favorites" can't collide with it.
 */
internal const val FAVORITES_EXPAND_KEY = "\u0000favorites"

/**
 * Whether a channel-list section renders expanded. While a search is active
 * every (match-containing) section acts expanded so matches stay visible,
 * regardless of the persisted expand state.
 */
internal fun isSectionExpanded(key: String, searchQuery: String, expandedKeys: Set<String>): Boolean =
    searchQuery.isNotBlank() || key in expandedKeys

/** Pure expand-key toggle so the round-trip is unit-testable without a ViewModel. */
internal fun toggleExpandKey(keys: Set<String>, key: String): Set<String> =
    if (key in keys) keys - key else keys + key

/**
 * Every section key in on-screen order: Favorites sentinel first, then the
 * groups as rendered. Used by Expand All; unit-tested for the ordering and
 * sentinel-non-collision contract.
 */
internal fun allSectionKeys(groupNames: List<String>): List<String> =
    listOf(FAVORITES_EXPAND_KEY) + groupNames

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val vlcPlayer: VLCPlayerWrapper,
    val playlistManager: CustomPlaylistManager,
    private val persistenceService: PersistenceService,
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

    /**
     * Expand-keys of the currently expanded sections (beads_adagio-59p.4.1).
     * Empty by default so a fresh install shows only collapsed headers;
     * persisted in [com.adagiostream.android.model.AppSettings.expandedGroups].
     */
    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    init {
        viewModelScope.launch {
            _expandedGroups.value = persistenceService.loadSettings().expandedGroups
        }
    }

    fun toggleGroupExpanded(key: String) {
        setExpandedGroups(toggleExpandKey(_expandedGroups.value, key))
    }

    fun expandAllGroups() {
        setExpandedGroups(allSectionKeys(filteredGroups.value.map { it.name }).toSet())
    }

    fun collapseAllGroups() {
        setExpandedGroups(emptySet())
    }

    private fun setExpandedGroups(keys: Set<String>) {
        // While searching, every section renders expanded regardless of stored
        // state, so any expand-state mutation would silently corrupt it: a
        // header toggle or Collapse All changes nothing visibly yet rewrites
        // the persisted set, and Expand All would persist only the
        // search-filtered subset of groups. Guarded here so every mutation
        // path is a no-op mid-search.
        if (_searchQuery.value.isNotBlank()) return
        _expandedGroups.value = keys
        viewModelScope.launch {
            val settings = persistenceService.loadSettings()
            persistenceService.saveSettings(settings.copy(expandedGroups = keys))
        }
    }

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
