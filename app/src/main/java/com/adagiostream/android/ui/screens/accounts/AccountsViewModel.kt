package com.adagiostream.android.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.service.account.AccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountManager: AccountManager,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountManager.accounts
    val isLoading: StateFlow<Boolean> = accountManager.isLoading

    fun toggleAccountEnabled(accountId: String) {
        viewModelScope.launch {
            accountManager.toggleAccountEnabled(accountId)
        }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            accountManager.deleteAccount(accountId)
        }
    }

    fun reload() {
        viewModelScope.launch {
            accountManager.loadAllChannels()
        }
    }
}
