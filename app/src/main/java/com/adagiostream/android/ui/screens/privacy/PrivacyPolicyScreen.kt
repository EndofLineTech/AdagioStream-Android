package com.adagiostream.android.ui.screens.privacy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Privacy Policy") },
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
            // Overview
            SectionTitle("Overview")
            Text(
                text = "Adagio Stream does not collect, store, or transmit any personal data to us. " +
                    "The app does not include analytics, advertising, crash reporting, or tracking of any kind.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // Data Stored on Your Device
            SectionTitle("Data Stored on Your Device")

            DataItemCard("Provider Credentials", "Server URLs, usernames, and passwords for your streaming accounts, stored in encrypted local storage.")
            DataItemCard("Favorites and Playlists", "Your favorite channels, custom playlists, and loved songs.")
            DataItemCard("Preferences", "App settings including appearance, sorting, buffer duration, and startup stream preferences.")
            DataItemCard("Cached Images", "Channel logos and album artwork cached locally for performance.")
            DataItemCard("Debug Logs", "Optional diagnostic logs that you can enable in Settings. Never transmitted automatically.")

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All data is stored locally on your device and is never transmitted to us.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // External Services
            SectionTitle("External Services")

            DataItemCard("Streaming Providers", "Audio streams are fetched directly from your configured providers. We do not proxy, intercept, or log these connections.")
            DataItemCard("ESPN.com API", "Live sports scores are fetched from ESPN's public API to display game information alongside sports channels.")
            DataItemCard("xmplaylist.com API", "Track metadata for SiriusXM channels is fetched from xmplaylist.com to display current song information.")

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All third-party API requests include only a generic app user-agent header. " +
                    "No cookies, device identifiers, or personal information are transmitted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // Tracking and Analytics
            SectionTitle("Tracking and Analytics")

            Text(
                text = "\u2022 No analytics or telemetry frameworks are included in this app.\n" +
                    "\u2022 No advertising SDKs or ad networks are used.\n" +
                    "\u2022 No device identifiers, IP addresses, or usage data are collected or transmitted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // Data Sharing
            SectionTitle("Data Sharing")
            Text(
                text = "We do not collect, sell, share, or transfer any personal data to third parties.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // Your Data Rights
            SectionTitle("Your Data Rights")

            DataItemCard("Access", "You can view all data stored by the app at any time through the Settings screen.")
            DataItemCard("Data Portability", "You can export all your data as a JSON file using the Export My Data option in Settings > Advanced.")
            DataItemCard("Erasure", "You can delete all app data at any time using the Delete All My Data option in Settings > Advanced, or by uninstalling the app.")
            DataItemCard("Rectification", "You can edit or correct any stored data directly through the app interface.")

            SectionDivider()

            // Data Retention
            SectionTitle("Data Retention")
            Text(
                text = "All data is stored exclusively on your device. There is no cloud sync, remote backup, or server-side storage. " +
                    "Data persists until you delete it or uninstall the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // Children's Privacy
            SectionTitle("Children's Privacy")
            Text(
                text = "This app does not knowingly collect any personal information from children under 13. " +
                    "Since the app does not collect any personal data from any user, it is compliant with COPPA and similar regulations.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // Contact
            SectionTitle("Contact")
            Text(
                text = "If you have questions about this privacy policy, contact us at:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            val context = LocalContext.current
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )) {
                        append("curt@lecaptain.org")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:curt@lecaptain.org"))
                    context.startActivity(intent)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Last updated April 15, 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(20.dp))
}

@Composable
private fun DataItemCard(title: String, description: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
