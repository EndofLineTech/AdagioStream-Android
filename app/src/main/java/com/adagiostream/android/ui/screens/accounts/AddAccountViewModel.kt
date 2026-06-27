package com.adagiostream.android.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
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
    private val navidromeApiFactory: NavidromeApiFactory,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editAccountId: String? = savedStateHandle.get<String>("accountId")
    val isEditing: Boolean = editAccountId != null

    private val _isXtream = MutableStateFlow(false)
    val isXtream: StateFlow<Boolean> = _isXtream.asStateFlow()

    private val _isSubsonic = MutableStateFlow(false)
    val isSubsonic: StateFlow<Boolean> = _isSubsonic.asStateFlow()

    /** State of the Subsonic "Test Connection" flow. */
    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

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

    private val _stripStreamIDs = MutableStateFlow(false)
    val stripStreamIDs: StateFlow<Boolean> = _stripStreamIDs.asStateFlow()

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
                        _stripStreamIDs.value = type.stripStreamIDs
                    }
                    is AccountType.M3U -> {
                        _isXtream.value = false
                        _m3uUrl.value = type.url
                        _epgUrl.value = type.epgUrl ?: ""
                    }
                    is AccountType.Subsonic -> {
                        _isSubsonic.value = true
                        _host.value = type.host
                        _username.value = type.username
                        _password.value = type.password
                        // Edits must be re-validated before saving.
                        _connectionTestState.value = ConnectionTestState.Idle
                    }
                }
            }
        }
    }

    fun setIsXtream(value: Boolean) {
        _isXtream.value = value
        if (value) _isSubsonic.value = false
        resetConnectionTest()
    }

    fun setIsSubsonic(value: Boolean) {
        _isSubsonic.value = value
        if (value) _isXtream.value = false
        resetConnectionTest()
    }

    fun setName(value: String) { _name.value = value }
    fun setM3uUrl(value: String) { _m3uUrl.value = value }
    fun setHost(value: String) { _host.value = value; resetConnectionTest() }
    fun setUsername(value: String) { _username.value = value; resetConnectionTest() }
    fun setPassword(value: String) { _password.value = value; resetConnectionTest() }
    fun setEpgUrl(value: String) { _epgUrl.value = value }
    fun setStripStreamIDs(value: Boolean) { _stripStreamIDs.value = value }
    fun dismissResult() {
        _addAccountResult.value = null
        _saveComplete.value = true
    }

    /** Resets the Subsonic connection-test state to [ConnectionTestState.Idle]. */
    private fun resetConnectionTest() {
        if (_connectionTestState.value != ConnectionTestState.Idle) {
            _connectionTestState.value = ConnectionTestState.Idle
        }
    }

    /** True when the entered Subsonic host uses cleartext http (drives the in-UI warning). */
    fun isHostCleartextHttp(): Boolean =
        _host.value.trim().startsWith("http://", ignoreCase = true)

    fun isValid(): Boolean {
        return when {
            _isSubsonic.value ->
                // Display name is optional for Subsonic; credentials are required.
                _host.value.isNotBlank() && _username.value.isNotBlank() && _password.value.isNotBlank()
            _isXtream.value ->
                _name.value.isNotBlank() && _host.value.isNotBlank() &&
                    _username.value.isNotBlank() && _password.value.isNotBlank()
            else ->
                _name.value.isNotBlank() && _m3uUrl.value.isNotBlank()
        }
    }

    /**
     * Validates Subsonic server reachability + credentials via `ping.view`.
     *
     * Runs the idle→testing→success/error state machine. Distinct, user-facing
     * messages are produced per [NavidromeApiException] case.
     */
    fun testConnection() {
        val host = _host.value.trim()
        val username = _username.value.trim()
        val password = _password.value
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            _connectionTestState.value =
                ConnectionTestState.Error("Enter server URL, username, and password first")
            return
        }

        _connectionTestState.value = ConnectionTestState.Testing
        viewModelScope.launch {
            _connectionTestState.value = try {
                UrlSanitizer.requireHttpUrl(host)
                val api = navidromeApiFactory.create(host, username, password)
                api.ping()
                ConnectionTestState.Success
            } catch (e: NavidromeApiException) {
                ConnectionTestState.Error(messageFor(e))
            } catch (e: IllegalArgumentException) {
                ConnectionTestState.Error("Enter a valid http or https URL")
            } catch (e: Exception) {
                ConnectionTestState.Error("Connection failed. Please try again.")
            }
        }
    }

    /** Maps a [NavidromeApiException] to a distinct, user-facing message. */
    private fun messageFor(e: NavidromeApiException): String = when (e) {
        is NavidromeApiException.AuthFailed ->
            "Incorrect username or password"
        is NavidromeApiException.Unreachable,
        is NavidromeApiException.TimedOut ->
            "Can't reach server — check the URL"
        is NavidromeApiException.NotSubsonicServer ->
            "That doesn't look like a Subsonic/Navidrome server"
        is NavidromeApiException.ServerError ->
            "Server error (HTTP ${e.statusCode}). Try again later."
        is NavidromeApiException.SubsonicError ->
            "Server rejected the request (code ${e.code})."
        is NavidromeApiException.InvalidUrl ->
            "Enter a valid server URL"
        is NavidromeApiException.DecodingError ->
            "Unexpected response from the server"
    }

    fun save() {
        if (!isValid()) {
            _errorMessage.value = "Please fill in all required fields"
            return
        }

        // Subsonic accounts must pass a connection test before they can be saved.
        if (_isSubsonic.value && _connectionTestState.value !is ConnectionTestState.Success) {
            _errorMessage.value = "Test the connection before saving"
            return
        }

        _isSaving.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                when {
                    _isSubsonic.value -> UrlSanitizer.requireHttpUrl(_host.value.trim())
                    _isXtream.value -> UrlSanitizer.requireHttpUrl(_host.value.trim())
                    else -> {
                        UrlSanitizer.requireHttpUrl(_m3uUrl.value.trim())
                        val epg = _epgUrl.value.trim()
                        if (epg.isNotBlank()) UrlSanitizer.requireHttpUrl(epg)
                    }
                }

                val type = when {
                    _isSubsonic.value -> AccountType.Subsonic(
                        host = _host.value.trim(),
                        username = _username.value.trim(),
                        // Password is NOT trimmed — the Subsonic token is MD5(password+salt)
                        // and must match the server's stored password byte-for-byte.
                        password = _password.value,
                    )
                    _isXtream.value -> AccountType.XtreamCodes(
                        host = _host.value.trim(),
                        username = _username.value.trim(),
                        password = _password.value.trim(),
                        stripStreamIDs = _stripStreamIDs.value,
                    )
                    else -> AccountType.M3U(
                        url = _m3uUrl.value.trim(),
                        epgUrl = _epgUrl.value.trim().ifBlank { null },
                    )
                }

                // Display name is optional for Subsonic; fall back to the host.
                val resolvedName = _name.value.trim().ifBlank {
                    if (_isSubsonic.value) hostLabel(_host.value.trim()) else _name.value.trim()
                }

                val account = Account(
                    id = editAccountId ?: UUID.randomUUID().toString(),
                    name = resolvedName,
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

    /**
     * Extracts the host portion of a URL for use as a default display name
     * (e.g. `https://music.example.com:4533/` → `music.example.com`).
     * Falls back to "Navidrome" if the host can't be parsed.
     */
    private fun hostLabel(url: String): String {
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }
        return host?.takeIf { it.isNotBlank() } ?: "Navidrome"
    }
}
