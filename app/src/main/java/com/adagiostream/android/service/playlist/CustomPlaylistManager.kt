package com.adagiostream.android.service.playlist

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.CustomPlaylistEntry
import com.adagiostream.android.model.CustomPlaylistGroup
import com.adagiostream.android.service.persistence.PersistenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomPlaylistManager @Inject constructor(
    private val persistenceService: PersistenceService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playlists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
    val playlists: StateFlow<List<CustomPlaylist>> = _playlists.asStateFlow()

    init {
        scope.launch {
            _playlists.value = persistenceService.loadCustomPlaylists()
        }
    }

    private fun persist() {
        scope.launch {
            persistenceService.saveCustomPlaylists(_playlists.value)
        }
    }

    private inline fun mutate(block: MutableList<CustomPlaylist>.() -> Unit) {
        val list = _playlists.value.toMutableList()
        list.block()
        _playlists.value = list
        persist()
    }

    // Playlist operations

    fun createPlaylist(name: String): CustomPlaylist {
        val playlist = CustomPlaylist(name = name)
        mutate { add(playlist) }
        return playlist
    }

    fun renamePlaylist(playlistId: String, name: String) {
        mutate {
            val i = indexOfFirst { it.id == playlistId }
            if (i >= 0) this[i] = this[i].copy(name = name, updatedAt = System.currentTimeMillis())
        }
    }

    fun deletePlaylist(playlistId: String) {
        mutate { removeAll { it.id == playlistId } }
    }

    fun movePlaylists(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        mutate {
            val item = removeAt(fromIndex)
            add(toIndex, item)
        }
    }

    // Group operations

    fun addGroup(name: String, playlistId: String): CustomPlaylistGroup? {
        val group = CustomPlaylistGroup(name = name)
        mutate {
            val i = indexOfFirst { it.id == playlistId }
            if (i >= 0) {
                val playlist = this[i]
                this[i] = playlist.copy(
                    groups = playlist.groups + group,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        return group
    }

    fun renameGroup(groupId: String, name: String, playlistId: String) {
        mutatePlaylist(playlistId) { playlist ->
            playlist.copy(groups = playlist.groups.map {
                if (it.id == groupId) it.copy(name = name) else it
            })
        }
    }

    fun deleteGroup(groupId: String, playlistId: String) {
        mutatePlaylist(playlistId) { playlist ->
            playlist.copy(groups = playlist.groups.filter { it.id != groupId })
        }
    }

    fun moveGroups(fromIndex: Int, toIndex: Int, playlistId: String) {
        if (fromIndex == toIndex) return
        mutatePlaylist(playlistId) { playlist ->
            val groups = playlist.groups.toMutableList()
            val item = groups.removeAt(fromIndex)
            groups.add(toIndex, item)
            playlist.copy(groups = groups)
        }
    }

    // Entry operations

    fun addEntry(entry: CustomPlaylistEntry, groupId: String, playlistId: String) {
        mutatePlaylist(playlistId) { playlist ->
            playlist.copy(groups = playlist.groups.map { group ->
                if (group.id == groupId) group.copy(entries = group.entries + entry) else group
            })
        }
    }

    fun removeEntry(entryId: String, groupId: String, playlistId: String) {
        mutatePlaylist(playlistId) { playlist ->
            playlist.copy(groups = playlist.groups.map { group ->
                if (group.id == groupId) group.copy(entries = group.entries.filter { it.id != entryId }) else group
            })
        }
    }

    fun moveEntries(fromIndex: Int, toIndex: Int, groupId: String, playlistId: String) {
        if (fromIndex == toIndex) return
        mutatePlaylist(playlistId) { playlist ->
            playlist.copy(groups = playlist.groups.map { group ->
                if (group.id == groupId) {
                    val entries = group.entries.toMutableList()
                    val item = entries.removeAt(fromIndex)
                    entries.add(toIndex, item)
                    group.copy(entries = entries)
                } else group
            })
        }
    }

    // Convenience

    fun addChannel(channel: Channel, groupId: String, playlistId: String) {
        addEntry(CustomPlaylistEntry(channel), groupId, playlistId)
    }

    // M3U Export

    fun exportAsM3U(playlistId: String): String {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return ""
        val sb = StringBuilder("#EXTM3U\n")
        for (group in playlist.groups) {
            for (entry in group.entries) {
                val logo = entry.logoURL?.let { " tvg-logo=\"$it\"" } ?: ""
                sb.append("#EXTINF:-1$logo group-title=\"${group.name}\",${entry.name}\n")
                sb.append("${entry.streamURL}\n")
            }
        }
        return sb.toString()
    }

    // Internal helper

    private inline fun mutatePlaylist(playlistId: String, transform: (CustomPlaylist) -> CustomPlaylist) {
        mutate {
            val i = indexOfFirst { it.id == playlistId }
            if (i >= 0) this[i] = transform(this[i]).copy(updatedAt = System.currentTimeMillis())
        }
    }
}
