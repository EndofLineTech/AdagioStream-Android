package com.adagiostream.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.TextSizeMode
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.util.DebugLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val persistenceService: PersistenceService,
    private val accountManager: AccountManager,
    private val vlcPlayerWrapper: VLCPlayerWrapper,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val accountCount: StateFlow<Int> = accountManager.accounts
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val channelCount: StateFlow<Int> = accountManager.channels
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val favoritesCount: StateFlow<Int> = accountManager.channels
        .map { channels -> channels.count { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val bitrateKbps: StateFlow<Float> = vlcPlayerWrapper.bitrateKbps
    val playbackState: StateFlow<PlaybackState> = vlcPlayerWrapper.playbackState

    val favoriteChannels: StateFlow<List<Channel>> = accountManager.channels
        .map { channels -> channels.filter { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debugLogSize = MutableStateFlow(DebugLogger.logFileSize())

    init {
        viewModelScope.launch {
            val loaded = persistenceService.loadSettings()
            _settings.value = loaded.copy(
                bufferDurationSeconds = loaded.bufferDurationSeconds.coerceIn(5, 15),
            )
            DebugLogger.isEnabled = loaded.debugLoggingEnabled
        }
    }

    fun updateBufferDuration(seconds: Int) {
        val clamped = seconds.coerceIn(5, 15)
        _settings.value = _settings.value.copy(bufferDurationSeconds = clamped)
        vlcPlayerWrapper.updateBufferDuration(clamped)
        save()
    }

    fun updateAppearanceMode(mode: AppearanceMode) {
        _settings.value = _settings.value.copy(appearanceMode = mode)
        save()
    }

    fun updateTextSizeMode(mode: TextSizeMode) {
        _settings.value = _settings.value.copy(textSizeMode = mode)
        save()
    }

    fun updateSortMode(mode: SortMode) {
        _settings.value = _settings.value.copy(sortMode = mode)
        accountManager.updateSortMode(mode)
        save()
    }

    fun updateGroupSortMode(mode: SortMode) {
        _settings.value = _settings.value.copy(groupSortMode = mode)
        accountManager.updateGroupSortMode(mode)
        save()
    }

    fun updateSortPrefixes(prefixes: List<String>) {
        _settings.value = _settings.value.copy(sortPrefixes = prefixes)
        accountManager.updateSortPrefixes(prefixes)
        save()
    }

    fun updateDebugLogging(enabled: Boolean) {
        _settings.value = _settings.value.copy(debugLoggingEnabled = enabled)
        DebugLogger.isEnabled = enabled
        save()
    }

    fun clearDebugLogs() {
        DebugLogger.clearLogs()
        debugLogSize.value = DebugLogger.logFileSize()
    }

    fun refreshDebugLogSize() {
        debugLogSize.value = DebugLogger.logFileSize()
    }

    fun updateStartupStream(channelId: String?) {
        _settings.value = _settings.value.copy(startupStreamID = channelId)
        save()
    }

    val channels: StateFlow<List<Channel>> = accountManager.channels

    fun playStartupStream() {
        val streamId = _settings.value.startupStreamID ?: return
        val channel = accountManager.channels.value.find { it.id == streamId } ?: return
        vlcPlayerWrapper.setChannelList(accountManager.channels.value)
        vlcPlayerWrapper.play(channel)
    }

    fun clearAllFavorites() {
        viewModelScope.launch {
            accountManager.clearAllFavorites()
        }
    }

    private fun save() {
        viewModelScope.launch {
            persistenceService.saveSettings(_settings.value)
        }
    }
}
