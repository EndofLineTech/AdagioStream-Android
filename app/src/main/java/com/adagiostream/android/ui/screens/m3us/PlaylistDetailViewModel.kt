package com.adagiostream.android.ui.screens.m3us

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.CustomPlaylistEntry
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistManager: CustomPlaylistManager,
    private val vlcPlayer: VLCPlayerWrapper,
) : ViewModel() {

    val playlistId: String = savedStateHandle["playlistId"] ?: ""

    val playlist: StateFlow<CustomPlaylist?> = playlistManager.playlists
        .map { list -> list.find { it.id == playlistId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = playlistManager.playlists.value.find { it.id == playlistId },
        )

    fun addGroup(name: String) = playlistManager.addGroup(name, playlistId)
    fun renameGroup(groupId: String, name: String) = playlistManager.renameGroup(groupId, name, playlistId)
    fun deleteGroup(groupId: String) = playlistManager.deleteGroup(groupId, playlistId)

    fun addManualEntry(name: String, url: String, logoUrl: String?, groupId: String) {
        val entry = CustomPlaylistEntry(
            name = name,
            streamURL = url,
            logoURL = logoUrl?.ifBlank { null },
        )
        playlistManager.addEntry(entry, groupId, playlistId)
    }

    fun removeEntry(entryId: String, groupId: String) = playlistManager.removeEntry(entryId, groupId, playlistId)

    fun playEntry(entry: CustomPlaylistEntry) {
        val channel = entry.asChannel()
        // Build channel list from all entries in the playlist for next/prev
        val allChannels = playlist.value?.groups?.flatMap { g -> g.entries.map { it.asChannel() } } ?: listOf(channel)
        vlcPlayer.setChannelList(allChannels)
        vlcPlayer.play(channel)
    }

    fun exportAsM3U(): String = playlistManager.exportAsM3U(playlistId)
}
