package com.adagiostream.android.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Provider
import com.adagiostream.android.model.ProviderType
import com.adagiostream.android.service.provider.ProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddProviderViewModel @Inject constructor(
    private val providerManager: ProviderManager,
) : ViewModel() {

    private val _isXtream = MutableStateFlow(false)
    val isXtream: StateFlow<Boolean> = _isXtream.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _m3uUrl = MutableStateFlow("")
    val m3uUrl: StateFlow<String> = _m3uUrl.asStateFlow()

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveComplete = MutableStateFlow(false)
    val saveComplete: StateFlow<Boolean> = _saveComplete.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setIsXtream(value: Boolean) { _isXtream.value = value }
    fun setName(value: String) { _name.value = value }
    fun setM3uUrl(value: String) { _m3uUrl.value = value }
    fun setHost(value: String) { _host.value = value }
    fun setUsername(value: String) { _username.value = value }
    fun setPassword(value: String) { _password.value = value }

    fun isValid(): Boolean {
        if (_name.value.isBlank()) return false
        return if (_isXtream.value) {
            _host.value.isNotBlank() && _username.value.isNotBlank() && _password.value.isNotBlank()
        } else {
            _m3uUrl.value.isNotBlank()
        }
    }

    fun save() {
        if (!isValid()) {
            _errorMessage.value = "Please fill in all required fields"
            return
        }

        _isSaving.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val type = if (_isXtream.value) {
                    ProviderType.XtreamCodes(
                        host = _host.value.trim(),
                        username = _username.value.trim(),
                        password = _password.value.trim(),
                    )
                } else {
                    ProviderType.M3U(url = _m3uUrl.value.trim())
                }

                val provider = Provider(
                    id = UUID.randomUUID().toString(),
                    name = _name.value.trim(),
                    type = type,
                )

                providerManager.addProvider(provider)
                _saveComplete.value = true
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to save provider"
            } finally {
                _isSaving.value = false
            }
        }
    }
}
