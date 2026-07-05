package com.adagiostream.android.ui.screens.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.player.VLCPlayerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A single channel's row in the multi-channel guide: current program + a short look-ahead. */
data class GuideChannelInfo(
    val channel: Channel,
    val current: EPGEntry?,
    val upcoming: List<EPGEntry>,
)

data class GuideGroup(
    val name: String,
    val channels: List<GuideChannelInfo>,
)

/**
 * Backs the full multi-channel EPG guide screen (Android port of iOS's channel-list-with-programs
 * guide). Reuses [AccountManager]'s existing channel/EPG data — no new data plumbing.
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val vlcPlayer: VLCPlayerWrapper,
) : ViewModel() {

    val isLoading: StateFlow<Boolean> = accountManager.isLoading
    val error: StateFlow<String?> = accountManager.error

    // Ticks periodically so the "now" line (current/upcoming program selection) stays fresh as
    // time passes, without requiring the user to leave and re-enter the screen.
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_TICK_MS)
        }
    }

    val groups: StateFlow<List<GuideGroup>> = combine(
        accountManager.groups,
        accountManager.epgEntries,
        clock,
    ) { groups, epg, now ->
        groups.map { group ->
            GuideGroup(
                name = group.name,
                channels = group.channels.map { channel ->
                    val epgId = channel.epgChannelID ?: channel.id
                    buildChannelInfo(channel, epg[epgId].orEmpty(), now)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() {
        viewModelScope.launch {
            accountManager.loadAllChannels()
        }
    }

    /** Tunes to [channel], mirroring ChannelsViewModel.playChannel's group-context lookup. */
    fun playChannel(channel: Channel) {
        val groupChannels = accountManager.groups.value
            .find { it.name == channel.group }
            ?.channels
            ?: emptyList()
        vlcPlayer.setChannelList(groupChannels)
        vlcPlayer.play(channel)
    }

    companion object {
        internal const val UPCOMING_LIMIT = 3
        private const val CLOCK_TICK_MS = 30_000L

        /** Pure selection logic: which entry is airing now, and what's coming up next. */
        internal fun buildChannelInfo(
            channel: Channel,
            entries: List<EPGEntry>,
            now: Long,
        ): GuideChannelInfo {
            val current = entries.firstOrNull { now >= it.start && now < it.end }
            val upcoming = entries
                .filter { it.start > now }
                .sortedBy { it.start }
                .take(UPCOMING_LIMIT)
            return GuideChannelInfo(channel = channel, current = current, upcoming = upcoming)
        }
    }
}
