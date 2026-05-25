package com.adagiostream.android.ui.screens.m3us

import androidx.lifecycle.ViewModel
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MyM3UsViewModel @Inject constructor(
    private val playlistManager: CustomPlaylistManager,
) : ViewModel() {

    val playlists: StateFlow<List<CustomPlaylist>> = playlistManager.playlists

    fun createPlaylist(name: String) = playlistManager.createPlaylist(name)
    fun renamePlaylist(playlistId: String, name: String) = playlistManager.renamePlaylist(playlistId, name)
    fun deletePlaylist(playlistId: String) = playlistManager.deletePlaylist(playlistId)
    fun movePlaylists(from: Int, to: Int) = playlistManager.movePlaylists(from, to)
}
