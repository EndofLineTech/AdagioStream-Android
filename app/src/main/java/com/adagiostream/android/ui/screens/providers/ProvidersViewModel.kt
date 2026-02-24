package com.adagiostream.android.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Provider
import com.adagiostream.android.service.provider.ProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val providerManager: ProviderManager,
) : ViewModel() {

    val providers: StateFlow<List<Provider>> = providerManager.providers
    val isLoading: StateFlow<Boolean> = providerManager.isLoading

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            providerManager.deleteProvider(providerId)
        }
    }

    fun reload() {
        viewModelScope.launch {
            providerManager.loadAllChannels()
        }
    }
}
