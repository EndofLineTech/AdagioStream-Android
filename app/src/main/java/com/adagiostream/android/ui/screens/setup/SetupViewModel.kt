package com.adagiostream.android.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.util.UrlSanitizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class SetupStep {
    WELCOME,
    CONNECTION_TYPE,
    ACCOUNT_DETAILS,
    GROUP_SELECTION,
}

data class SetupGroupItem(
    val name: String,
    val isEnabled: Boolean,
    val channelCount: Int,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val persistenceService: PersistenceService,
    private val accountManager: AccountManager,
) : ViewModel() {

    val hasExistingAccounts: Boolean
        get() = accountManager.accounts.value.isNotEmpty()

    val existingAccountCount: Int
        get() = accountManager.accounts.value.size

    private val _currentStep = MutableStateFlow(SetupStep.WELCOME)
    val currentStep: StateFlow<SetupStep> = _currentStep.asStateFlow()

    private val _isXtream = MutableStateFlow(false)
    val isXtream: StateFlow<Boolean> = _isXtream.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _epgUrl = MutableStateFlow("")
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    private val _addedGroupCount = MutableStateFlow(0)
    val addedGroupCount: StateFlow<Int> = _addedGroupCount.asStateFlow()

    val groupItems: StateFlow<List<SetupGroupItem>> = combine(
        accountManager.allGroupNames,
        accountManager.channels,
        accountManager.groups,
    ) { allGroups, channels, _ ->
        val channelCounts = channels.groupBy { it.group }.mapValues { it.value.size }
        allGroups.map { name ->
            SetupGroupItem(
                name = name,
                isEnabled = accountManager.isGroupEnabled(name),
                channelCount = channelCounts[name] ?: 0,
            )
        }.sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setIsXtream(value: Boolean) { _isXtream.value = value }
    fun setName(value: String) { _name.value = value }
    fun setUrl(value: String) { _url.value = value }
    fun setUsername(value: String) { _username.value = value }
    fun setPassword(value: String) { _password.value = value }
    fun setEpgUrl(value: String) { _epgUrl.value = value }

    fun selectConnectionTypeAndAdvance(isXtream: Boolean) {
        _isXtream.value = isXtream
        _currentStep.value = SetupStep.ACCOUNT_DETAILS
    }

    fun goToConnectionType() {
        _currentStep.value = SetupStep.CONNECTION_TYPE
    }

    fun goToAccountDetails() {
        _currentStep.value = SetupStep.ACCOUNT_DETAILS
    }

    fun goBack() {
        _currentStep.value = when (_currentStep.value) {
            SetupStep.WELCOME -> SetupStep.WELCOME
            SetupStep.CONNECTION_TYPE -> SetupStep.WELCOME
            SetupStep.ACCOUNT_DETAILS -> SetupStep.CONNECTION_TYPE
            SetupStep.GROUP_SELECTION -> SetupStep.ACCOUNT_DETAILS
        }
    }

    fun isFormValid(): Boolean {
        if (_name.value.isBlank()) return false
        return if (_isXtream.value) {
            _url.value.isNotBlank() && _username.value.isNotBlank() && _password.value.isNotBlank()
        } else {
            _url.value.isNotBlank()
        }
    }

    fun skip() {
        viewModelScope.launch {
            val settings = persistenceService.loadSettings()
            persistenceService.saveSettings(settings.copy(setupCompleted = true))
            _setupComplete.value = true
        }
    }

    fun saveAccount() {
        if (!isFormValid()) {
            _errorMessage.value = "Please fill in all required fields"
            return
        }

        _isSaving.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val type = if (_isXtream.value) {
                    AccountType.XtreamCodes(
                        host = _url.value.trim(),
                        username = _username.value.trim(),
                        password = _password.value.trim(),
                    )
                } else {
                    AccountType.M3U(
                        url = _url.value.trim(),
                        epgUrl = _epgUrl.value.trim().ifBlank { null },
                    )
                }

                val account = Account(
                    id = UUID.randomUUID().toString(),
                    name = _name.value.trim(),
                    type = type,
                )

                val result = accountManager.addAccount(account, enableAllGroups = true)
                _addedGroupCount.value = result.newGroupCount

                if (result.newGroupCount > 0) {
                    // Give the combine a moment to process the new data
                    withTimeoutOrNull(3000) {
                        groupItems.first { it.isNotEmpty() }
                    }
                    _currentStep.value = SetupStep.GROUP_SELECTION
                } else {
                    // No groups found, skip to finish
                    val settings = persistenceService.loadSettings()
                    persistenceService.saveSettings(settings.copy(setupCompleted = true))
                    _setupComplete.value = true
                }
            } catch (e: Exception) {
                _errorMessage.value = UrlSanitizer.redact(e.message ?: "Failed to save account")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun toggleGroupEnabled(groupName: String) {
        viewModelScope.launch {
            accountManager.toggleGroupEnabled(groupName)
        }
    }

    fun setAllGroupsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            accountManager.setAllGroupsEnabled(enabled)
        }
    }

    fun finishSetup() {
        viewModelScope.launch {
            val settings = persistenceService.loadSettings()
            persistenceService.saveSettings(settings.copy(setupCompleted = true))
            _setupComplete.value = true
        }
    }
}
