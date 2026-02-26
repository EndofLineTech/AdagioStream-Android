package com.adagiostream.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.model.TextSizeMode
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.provider.ProviderManager
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
    private val providerManager: ProviderManager,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val providerCount: StateFlow<Int> = providerManager.providers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val channelCount: StateFlow<Int> = providerManager.channels
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val favoritesCount: StateFlow<Int> = providerManager.channels
        .map { channels -> channels.count { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            _settings.value = persistenceService.loadSettings()
        }
    }

    fun updateBufferDuration(seconds: Int) {
        _settings.value = _settings.value.copy(bufferDurationSeconds = seconds)
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

    fun updateSortPrefixes(prefixes: List<String>) {
        _settings.value = _settings.value.copy(sortPrefixes = prefixes)
        providerManager.updateSortPrefixes(prefixes)
        save()
    }

    fun clearAllFavorites() {
        viewModelScope.launch {
            providerManager.clearAllFavorites()
        }
    }

    private fun save() {
        viewModelScope.launch {
            persistenceService.saveSettings(_settings.value)
        }
    }
}
