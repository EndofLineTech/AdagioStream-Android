package com.adagiostream.android.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.util.UrlSanitizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val accountManager: AccountManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editAccountId: String? = savedStateHandle.get<String>("accountId")
    val isEditing: Boolean = editAccountId != null

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

    private val _epgUrl = MutableStateFlow("")
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveComplete = MutableStateFlow(false)
    val saveComplete: StateFlow<Boolean> = _saveComplete.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _addAccountResult = MutableStateFlow<AccountManager.AddAccountResult?>(null)
    val addAccountResult: StateFlow<AccountManager.AddAccountResult?> = _addAccountResult.asStateFlow()

    init {
        if (editAccountId != null) {
            val account = accountManager.accounts.value.find { it.id == editAccountId }
            if (account != null) {
                _name.value = account.name
                when (val type = account.type) {
                    is AccountType.XtreamCodes -> {
                        _isXtream.value = true
                        _host.value = type.host
                        _username.value = type.username
                        _password.value = type.password
                    }
                    is AccountType.M3U -> {
                        _isXtream.value = false
                        _m3uUrl.value = type.url
                        _epgUrl.value = type.epgUrl ?: ""
                    }
                }
            }
        }
    }

    fun setIsXtream(value: Boolean) { _isXtream.value = value }
    fun setName(value: String) { _name.value = value }
    fun setM3uUrl(value: String) { _m3uUrl.value = value }
    fun setHost(value: String) { _host.value = value }
    fun setUsername(value: String) { _username.value = value }
    fun setPassword(value: String) { _password.value = value }
    fun setEpgUrl(value: String) { _epgUrl.value = value }
    fun dismissResult() {
        _addAccountResult.value = null
        _saveComplete.value = true
    }

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
                    AccountType.XtreamCodes(
                        host = _host.value.trim(),
                        username = _username.value.trim(),
                        password = _password.value.trim(),
                    )
                } else {
                    AccountType.M3U(
                        url = _m3uUrl.value.trim(),
                        epgUrl = _epgUrl.value.trim().ifBlank { null },
                    )
                }

                val account = Account(
                    id = editAccountId ?: UUID.randomUUID().toString(),
                    name = _name.value.trim(),
                    type = type,
                )

                if (isEditing) {
                    accountManager.updateAccount(account)
                    _saveComplete.value = true
                } else {
                    val result = accountManager.addAccount(account)
                    if (result.newGroupCount > 0) {
                        _addAccountResult.value = result
                    } else {
                        _saveComplete.value = true
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = UrlSanitizer.redact(e.message ?: "Failed to save account")
            } finally {
                _isSaving.value = false
            }
        }
    }
}
