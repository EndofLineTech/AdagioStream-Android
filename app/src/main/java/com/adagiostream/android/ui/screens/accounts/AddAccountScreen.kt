package com.adagiostream.android.ui.screens.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onBack: () -> Unit,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val isXtream by viewModel.isXtream.collectAsStateWithLifecycle()
    val isSubsonic by viewModel.isSubsonic.collectAsStateWithLifecycle()
    val isAudiobookshelf by viewModel.isAudiobookshelf.collectAsStateWithLifecycle()
    val absDiscoveryState by viewModel.absDiscoveryState.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val m3uUrl by viewModel.m3uUrl.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val epgUrl by viewModel.epgUrl.collectAsStateWithLifecycle()
    val stripStreamIDs by viewModel.stripStreamIDs.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveComplete by viewModel.saveComplete.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val addAccountResult by viewModel.addAccountResult.collectAsStateWithLifecycle()
    val connectionTestState by viewModel.connectionTestState.collectAsStateWithLifecycle()
    val ssoAuthorizeUrl by viewModel.ssoAuthorizeUrl.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(saveComplete) {
        if (saveComplete) onBack()
    }

    // SSO step 2: open the IdP authorization URL in a Chrome Custom Tab. The
    // IdP redirects back via adagiostream://oauth (MainActivity intent filter).
    LaunchedEffect(ssoAuthorizeUrl) {
        ssoAuthorizeUrl?.let { url ->
            viewModel.ssoLaunchHandled()
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
        }
    }

    addAccountResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissResult() },
            title = { Text("Account Added") },
            text = {
                Text(
                    "Loaded ${result.channelCount} channels in ${result.newGroupCount} new groups. " +
                        "New groups are hidden by default — enable them in Settings → Groups."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResult() }) {
                    Text("OK")
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (viewModel.isEditing) "Edit Account" else "Add Account") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (!viewModel.isEditing) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isXtream && !isSubsonic && !isAudiobookshelf,
                        onClick = {
                            viewModel.setIsSubsonic(false)
                            viewModel.setIsXtream(false)
                            viewModel.setIsAudiobookshelf(false)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                    ) {
                        Text("M3U")
                    }
                    SegmentedButton(
                        selected = isXtream,
                        onClick = { viewModel.setIsXtream(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                    ) {
                        Text("Xtream")
                    }
                    SegmentedButton(
                        selected = isSubsonic,
                        onClick = { viewModel.setIsSubsonic(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                    ) {
                        Text("Navidrome")
                    }
                    SegmentedButton(
                        selected = isAudiobookshelf,
                        onClick = { viewModel.setIsAudiobookshelf(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                    ) {
                        Text("Audiobooks")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            when {
                isAudiobookshelf -> AudiobookshelfForm(
                    host = host,
                    username = username,
                    password = password,
                    name = name,
                    passwordVisible = passwordVisible,
                    onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                    showHttpWarning = viewModel.isHostCleartextHttp(),
                    discoveryState = absDiscoveryState,
                    connectionTestState = connectionTestState,
                    onHostChange = { viewModel.setHost(it) },
                    onUsernameChange = { viewModel.setUsername(it) },
                    onPasswordChange = { viewModel.setPassword(it) },
                    onNameChange = { viewModel.setName(it) },
                    onCheckServer = { viewModel.discoverAbsServer() },
                    onTestConnection = { viewModel.testConnection() },
                    onSsoSignIn = { viewModel.startSso() },
                )

                isSubsonic -> SubsonicForm(
                    host = host,
                    username = username,
                    password = password,
                    name = name,
                    passwordVisible = passwordVisible,
                    onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                    showHttpWarning = viewModel.isHostCleartextHttp(),
                    connectionTestState = connectionTestState,
                    onHostChange = { viewModel.setHost(it) },
                    onUsernameChange = { viewModel.setUsername(it) },
                    onPasswordChange = { viewModel.setPassword(it) },
                    onNameChange = { viewModel.setName(it) },
                    onTestConnection = { viewModel.testConnection() },
                )

                else -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { viewModel.setName(it) },
                        label = { Text("Account Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isXtream) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { viewModel.setHost(it) },
                            label = { Text("Server URL") },
                            placeholder = { Text("http://example.com:8080") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { viewModel.setUsername(it) },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PasswordField(
                            value = password,
                            onValueChange = { viewModel.setPassword(it) },
                            passwordVisible = passwordVisible,
                            onToggle = { passwordVisible = !passwordVisible },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Clean up channel names",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = "Remove number prefixes from channel names (e.g. \"5204 | ESPN\", \"5204 - ESPN\" become \"ESPN\")",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = stripStreamIDs,
                                onCheckedChange = { viewModel.setStripStreamIDs(it) },
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = m3uUrl,
                            onValueChange = { viewModel.setM3uUrl(it) },
                            label = { Text("M3U URL") },
                            placeholder = { Text("http://example.com/playlist.m3u") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = epgUrl,
                            onValueChange = { viewModel.setEpgUrl(it) },
                            label = { Text("EPG URL (Optional)") },
                            placeholder = { Text("http://example.com/epg.xml") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // For Subsonic/Audiobookshelf, saving is gated on a successful connection test.
            val saveEnabled = !isSaving && viewModel.isValid() &&
                (!(isSubsonic || isAudiobookshelf) || connectionTestState is ConnectionTestState.Success)

            Button(
                onClick = { viewModel.save() },
                enabled = saveEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SubsonicForm(
    host: String,
    username: String,
    password: String,
    name: String,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    showHttpWarning: Boolean,
    connectionTestState: ConnectionTestState,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
    val testing = connectionTestState is ConnectionTestState.Testing

    OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        label = { Text("Server URL") },
        placeholder = { Text("http://192.168.1.10:4533") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !testing,
    )

    if (showHttpWarning) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "http is unencrypted. Your token auth is still safe, but the " +
                    "connection isn't private. Use https if your server supports it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Username") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !testing,
    )

    Spacer(modifier = Modifier.height(12.dp))
    PasswordField(
        value = password,
        onValueChange = onPasswordChange,
        passwordVisible = passwordVisible,
        onToggle = onPasswordVisibilityToggle,
        enabled = !testing,
    )

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Display Name (Optional)") },
        placeholder = { Text("My Navidrome") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !testing,
    )

    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = onTestConnection,
        enabled = !testing && host.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { contentDescription = "Test Connection" },
    ) {
        if (testing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Testing…")
        } else {
            Text("Test Connection")
        }
    }

    ConnectionTestResult(connectionTestState)
}

/**
 * Audiobookshelf add/edit form. Two-step flow: enter the host and check the
 * server (`GET /status` discovery), then — per the discovered auth methods —
 * sign in with username/password (Test Connection performs the login that
 * produces the stored token pair) and/or with SSO (the OIDC flow, labelled
 * with the server-configured button text).
 */
@Composable
private fun AudiobookshelfForm(
    host: String,
    username: String,
    password: String,
    name: String,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    showHttpWarning: Boolean,
    discoveryState: AbsDiscoveryState,
    connectionTestState: ConnectionTestState,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onCheckServer: () -> Unit,
    onTestConnection: () -> Unit,
    onSsoSignIn: () -> Unit,
) {
    val checking = discoveryState is AbsDiscoveryState.Checking
    val testing = connectionTestState is ConnectionTestState.Testing

    OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        label = { Text("Server URL") },
        placeholder = { Text("http://192.168.1.10:13378") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !checking && !testing,
    )

    if (showHttpWarning) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "http is unencrypted — your password and session tokens travel " +
                    "in the clear. Use https if your server supports it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = onCheckServer,
        enabled = !checking && !testing && host.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { contentDescription = "Check Server" },
    ) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Checking…")
        } else {
            Text("Check Server")
        }
    }

    when (discoveryState) {
        is AbsDiscoveryState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Server check failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = discoveryState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is AbsDiscoveryState.Discovered -> {
            if (discoveryState.supportsLocal) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !testing,
                )

                Spacer(modifier = Modifier.height(12.dp))
                PasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    passwordVisible = passwordVisible,
                    onToggle = onPasswordVisibilityToggle,
                    enabled = !testing,
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Display Name (Optional)") },
                    placeholder = { Text("My Audiobookshelf") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !testing,
                )

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !testing && host.isNotBlank() &&
                        username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Test Connection" },
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Testing…")
                    } else {
                        Text("Test Connection")
                    }
                }
            }

            if (discoveryState.supportsOpenId) {
                // SSO via the server-fronted OIDC flow; the label comes from
                // the server's authOpenIDButtonText when configured.
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onSsoSignIn,
                    enabled = !testing && host.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Sign in with SSO" },
                ) {
                    if (testing && !discoveryState.supportsLocal) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Signing in…")
                    } else {
                        Text(discoveryState.openIdButtonText)
                    }
                }
            }

            if (discoveryState.supportsLocal || discoveryState.supportsOpenId) {
                ConnectionTestResult(connectionTestState)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This server has no supported sign-in method.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        else -> Unit
    }
}

/** Inline success/error rows shown under a "Test Connection" button. */
@Composable
private fun ConnectionTestResult(state: ConnectionTestState) {
    when (state) {
        is ConnectionTestState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Connection successful",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Connection successful",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        is ConnectionTestState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Connection failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    passwordVisible: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            autoCorrectEnabled = false,
        ),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                )
            }
        },
    )
}
