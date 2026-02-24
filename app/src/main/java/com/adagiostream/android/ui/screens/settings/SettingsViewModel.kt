package com.adagiostream.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.service.persistence.PersistenceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val persistenceService: PersistenceService,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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

    private fun save() {
        viewModelScope.launch {
            persistenceService.saveSettings(_settings.value)
        }
    }
}
