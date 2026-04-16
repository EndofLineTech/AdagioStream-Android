package com.adagiostream.android.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.service.account.AccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupItem(
    val name: String,
    val isEnabled: Boolean,
    val isFavorite: Boolean,
    val channelCount: Int,
)

@HiltViewModel
class GroupManagementViewModel @Inject constructor(
    private val accountManager: AccountManager,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val groupItems: StateFlow<List<GroupItem>> = combine(
        accountManager.allGroupNames,
        accountManager.channels,
        accountManager.favoriteGroupNames,
        accountManager.enabledGroupNames,
        _searchQuery,
    ) { allGroups, channels, favoriteNames, enabledNames, query ->
        val channelCounts = channels.groupBy { it.group }.mapValues { it.value.size }
        allGroups
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .map { name ->
                GroupItem(
                    name = name,
                    isEnabled = enabledNames?.contains(name) ?: true,
                    isFavorite = name in favoriteNames,
                    channelCount = channelCounts[name] ?: 0,
                )
            }
            .sortedWith(compareByDescending<GroupItem> { it.isFavorite }.thenBy { it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleEnabled(groupName: String) {
        viewModelScope.launch {
            accountManager.toggleGroupEnabled(groupName)
        }
    }

    fun toggleFavorite(groupName: String) {
        viewModelScope.launch {
            accountManager.toggleGroupFavorite(groupName)
        }
    }

    fun setAllEnabled(enabled: Boolean) {
        viewModelScope.launch {
            accountManager.setAllGroupsEnabled(enabled)
        }
    }

    fun onReorder(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val favorites = groupItems.value.filter { it.isFavorite }.map { it.name }.toMutableList()
            if (fromIndex < favorites.size && toIndex < favorites.size) {
                val item = favorites.removeAt(fromIndex)
                favorites.add(toIndex, item)
                accountManager.updateFavoriteGroupOrder(favorites)
            }
        }
    }
}
