package com.adagiostream.android.service.account

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.CustomPlaylist

sealed interface RawChannelGroupInventory {
    data object Loading : RawChannelGroupInventory
    data object NoAccounts : RawChannelGroupInventory
    data class Complete(val groupCounts: Map<String, Int>) : RawChannelGroupInventory
    data class PartialFailure(
        val groupCounts: Map<String, Int>,
        val message: String,
    ) : RawChannelGroupInventory
}

internal fun rawCustomChannels(playlists: List<CustomPlaylist>): List<Channel> = playlists.flatMap { playlist ->
    playlist.groups.flatMap { group -> group.entries.map { it.asChannel(group.name) } }
}

internal fun rawChannelGroupCounts(
    providerChannels: List<Channel>,
    playlists: List<CustomPlaylist>,
): Map<String, Int> = buildMap {
    providerChannels.forEach { channel ->
        put(channel.group, getOrDefault(channel.group, 0) + 1)
    }
    playlists.forEach { playlist ->
        playlist.groups.forEach { group ->
            putIfAbsent(group.name, 0)
            put(group.name, getValue(group.name) + group.entries.size)
        }
    }
}
