package com.adagiostream.android.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()
    val setupComplete by viewModel.setupComplete.collectAsStateWithLifecycle()

    if (setupComplete) {
        onSetupComplete()
        return
    }

    BackHandler(enabled = currentStep != SetupStep.WELCOME) {
        viewModel.goBack()
    }

    AnimatedContent(
        targetState = currentStep,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val enter = slideInHorizontally { if (forward) it else -it } + fadeIn()
            val exit = slideOutHorizontally { if (forward) -it else it } + fadeOut()
            enter togetherWith exit
        },
        label = "setupStep",
    ) { step ->
        when (step) {
            SetupStep.WELCOME -> WelcomeStep(
                hasExistingAccounts = viewModel.hasExistingAccounts,
                existingAccountCount = viewModel.existingAccountCount,
                onGetStarted = { viewModel.goToConnectionType() },
                onSkip = { viewModel.skip() },
            )
            SetupStep.CONNECTION_TYPE -> ConnectionTypeStep(
                onSelectM3U = { viewModel.selectConnectionTypeAndAdvance(false) },
                onSelectXtream = { viewModel.selectConnectionTypeAndAdvance(true) },
                onBack = { viewModel.goBack() },
            )
            SetupStep.ACCOUNT_DETAILS -> AccountDetailsStep(
                viewModel = viewModel,
                onBack = { viewModel.goBack() },
            )
            SetupStep.GROUP_SELECTION -> GroupSelectionStep(
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun WelcomeStep(
    hasExistingAccounts: Boolean,
    existingAccountCount: Int,
    onGetStarted: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Icon(
            imageVector = Icons.Default.Radio,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Welcome to Adagio Stream",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (hasExistingAccounts) {
                val noun = if (existingAccountCount == 1) "account" else "accounts"
                "You already have $existingAccountCount $noun configured. You can add a new account or skip this setup."
            } else {
                "Stream your favorite audio channels from M3U playlists or Xtream Codes providers."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                if (hasExistingAccounts) "Add New Account" else "Get Started",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) {
            Text(
                if (hasExistingAccounts) "Keep Existing Setup" else "Skip Setup",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTypeStep(
    onSelectM3U: () -> Unit,
    onSelectXtream: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Choose Your Source",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "What type of connection will you be using?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(32.dp))

            SourceCard(
                title = "M3U Playlist",
                description = "Connect using a playlist URL",
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                onClick = onSelectM3U,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SourceCard(
                title = "Xtream Codes",
                description = "Connect with server URL, username, and password",
                icon = Icons.Default.Dns,
                onClick = onSelectXtream,
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailsStep(
    viewModel: SetupViewModel,
    onBack: () -> Unit,
) {
    val isXtream by viewModel.isXtream.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val epgUrl by viewModel.epgUrl.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (isXtream) "Xtream Codes" else "M3U Playlist",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter your connection details",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.setName(it) },
                label = { Text("Account Name") },
                placeholder = { Text("My Provider") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (isXtream) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { viewModel.setUrl(it) },
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
                OutlinedTextField(
                    value = password,
                    onValueChange = { viewModel.setPassword(it) },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                )
            } else {
                OutlinedTextField(
                    value = url,
                    onValueChange = { viewModel.setUrl(it) },
                    label = { Text("Playlist URL") },
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

            errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { viewModel.saveAccount() },
                enabled = !isSaving && viewModel.isFormValid(),
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
                    Text("Add Account")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSelectionStep(
    viewModel: SetupViewModel,
) {
    val groupItems by viewModel.groupItems.collectAsStateWithLifecycle()
    val addedGroupCount by viewModel.addedGroupCount.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            actions = {
                TextButton(onClick = { viewModel.finishSetup() }) {
                    Text("Skip")
                }
            },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = "Enable Groups",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We found ${groupItems.size} groups. Choose which ones to enable.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.setAllGroupsEnabled(true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enable All")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.setAllGroupsEnabled(false) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Disable All")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(groupItems, key = { it.name }) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = group.isEnabled,
                        onCheckedChange = { viewModel.toggleGroupEnabled(group.name) },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "${group.channelCount} channels",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.finishSetup() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(48.dp),
        ) {
            Text("Done", style = MaterialTheme.typography.titleMedium)
        }
    }
}
