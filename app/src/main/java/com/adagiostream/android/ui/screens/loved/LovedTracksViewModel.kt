package com.adagiostream.android.ui.screens.loved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.LovedTrack
import com.adagiostream.android.service.account.AccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LovedTracksViewModel @Inject constructor(
    private val accountManager: AccountManager,
) : ViewModel() {

    val lovedTracks: StateFlow<List<LovedTrack>> = accountManager.lovedTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeTrack(index: Int) {
        viewModelScope.launch {
            accountManager.removeLovedTrack(index)
        }
    }

    fun onReorder(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            accountManager.reorderLovedTracks(fromIndex, toIndex)
        }
    }
}
