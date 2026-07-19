package com.adagiostream.android.ui.screens.settings

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.adagiostream.android.BuildConfig
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.model.AutoSourceOrder
import com.adagiostream.android.model.ChannelGroupingMode
import com.adagiostream.android.model.ArtworkDisplayMode
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.model.SXMMetadataSource
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.TextSizeMode
import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeOrder
import com.adagiostream.android.util.BitrateFormatter
import com.adagiostream.android.util.DebugLogger
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToAccounts: (() -> Unit)? = null,
    onNavigateToGroups: (() -> Unit)? = null,
    onNavigateToLicenses: (() -> Unit)? = null,
    onNavigateToPrivacyPolicy: (() -> Unit)? = null,
    onNavigateToDownloads: (() -> Unit)? = null,
    onDataDeleted: (() -> Unit)? = null,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasAudiobookshelfAccount by viewModel.hasAudiobookshelfAccount.collectAsStateWithLifecycle()
    val accountCount by viewModel.accountCount.collectAsStateWithLifecycle()
    val channelCount by viewModel.channelCount.collectAsStateWithLifecycle()
    val favoritesCount by viewModel.favoritesCount.collectAsStateWithLifecycle()
    val bitrateKbps by viewModel.bitrateKbps.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val favoriteChannels by viewModel.favoriteChannels.collectAsStateWithLifecycle()
    var showClearFavoritesDialog by remember { mutableStateOf(false) }
    var showDeleteStep1 by remember { mutableStateOf(false) }
    var showDeleteStep2 by remember { mutableStateOf(false) }
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val exportJson by viewModel.exportJson.collectAsStateWithLifecycle()
    val dataDeleted by viewModel.dataDeleted.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (dataDeleted && onDataDeleted != null) {
        viewModel.clearDataDeleted()
        onDataDeleted()
        return
    }

    if (exportJson != null) {
        val json = exportJson!!
        viewModel.clearExportJson()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "AdagioStream Data Export")
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export My Data"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
        )

        // ── Accounts & Channels ──────────────────────────────

        SectionHeader("Accounts & Channels")

        if (onNavigateToAccounts != null) {
            Card(
                onClick = onNavigateToAccounts,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Manage Accounts",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "$accountCount configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Go to Accounts",
                    )
                }
            }
        }

        if (onNavigateToGroups != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                onClick = onNavigateToGroups,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Manage Groups",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Go to Groups",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Channels Loaded", channelCount.toString())
                StatRow("Favorites", favoritesCount.toString())
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.reloadChannels() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reload Channels")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { showClearFavoritesDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = favoritesCount > 0,
        ) {
            Text("Clear All Favorites ($favoritesCount)")
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()

        // ── Appearance ───────────────────────────────────────

        SectionHeader("Appearance")

        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppearanceMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.appearanceMode == mode,
                    onClick = { viewModel.updateAppearanceMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppearanceMode.entries.size,
                    ),
                ) {
                    Text(
                        text = when (mode) {
                            AppearanceMode.SYSTEM -> "System"
                            AppearanceMode.LIGHT -> "Light"
                            AppearanceMode.DARK -> "Dark"
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Text Size",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        var textSizeExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = textSizeExpanded,
            onExpandedChange = { textSizeExpanded = it },
        ) {
            OutlinedTextField(
                value = settings.textSizeMode.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = textSizeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = textSizeExpanded,
                onDismissRequest = { textSizeExpanded = false },
            ) {
                TextSizeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName) },
                        onClick = {
                            viewModel.updateTextSizeMode(mode)
                            textSizeExpanded = false
                        },
                    )
                }
            }
        }

        Text(
            text = "Preview: The quick brown fox jumps over the lazy dog",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Artwork Display",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ArtworkDisplayMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.artworkDisplayMode == mode,
                    onClick = { viewModel.updateArtworkDisplayMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ArtworkDisplayMode.entries.size,
                    ),
                ) {
                    Text(text = mode.displayName)
                }
            }
        }

        FooterText("Choose whether to display track cover art or the channel logo.")

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Channel Grouping",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ChannelGroupingMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.channelGroupingMode == mode,
                    onClick = { viewModel.updateChannelGroupingMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ChannelGroupingMode.entries.size,
                    ),
                ) {
                    Text(text = mode.displayName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        FooterText(
            "All Groups merges channels from all accounts into shared groups. " +
                "By Provider keeps each account's groups separate. " +
                "By Source shows the original group from each account.",
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Channel Sorting",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SortMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.sortMode == mode,
                    onClick = { viewModel.updateSortMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SortMode.entries.size,
                    ),
                ) {
                    Text(text = mode.displayName)
                }
            }
        }

        FooterText("Controls the order channels appear within each group.")

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Group Sorting",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SortMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.groupSortMode == mode,
                    onClick = { viewModel.updateGroupSortMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SortMode.entries.size,
                    ),
                ) {
                    Text(text = mode.displayName)
                }
            }
        }

        FooterText("Controls the order groups appear in your channel list. Favorite groups always appear first.")

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Sort Prefixes",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SortPrefixEditor(
            prefixes = settings.sortPrefixes,
            onPrefixesChanged = { viewModel.updateSortPrefixes(it) },
        )

        FooterText("Channel names starting with these prefixes will be sorted by the text after the prefix (e.g. 'Radio: Jazz' sorts under J).")

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()

        // ── Playback ─────────────────────────────────────────

        SectionHeader("Playback")

        Text(
            text = "Stream Quality",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val qualityText = when (playbackState) {
            is PlaybackState.Playing, is PlaybackState.Buffering -> {
                val formatted = BitrateFormatter.format(bitrateKbps)
                formatted.ifEmpty { "Measuring..." }
            }
            else -> "Not playing"
        }
        Text(
            text = qualityText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Buffer Duration",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = settings.bufferDurationSeconds.toFloat(),
                onValueChange = { viewModel.updateBufferDuration(it.roundToInt()) },
                valueRange = 5f..15f,
                steps = 9,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${settings.bufferDurationSeconds}s",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Startup Stream",
            style = MaterialTheme.typography.titleMedium,
        )
        FooterText("Auto-play a favorite channel on launch.")
        Spacer(modifier = Modifier.height(8.dp))

        StartupStreamPicker(
            selectedId = settings.startupStreamID,
            favorites = favoriteChannels,
            onSelected = { viewModel.updateStartupStream(it) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Live Sports Score Updates",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val espnOptions = listOf(0, 5, 10, 15, 30)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            espnOptions.forEachIndexed { index, seconds ->
                SegmentedButton(
                    selected = settings.espnPollingIntervalSeconds == seconds,
                    onClick = { viewModel.updateEspnPollingInterval(seconds) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = espnOptions.size,
                    ),
                ) {
                    Text(text = if (seconds == 0) "Off" else "${seconds}s")
                }
            }
        }

        FooterText("How often to refresh live sports scores from ESPN.com API. Off disables score updates entirely. Lower values show scores sooner but use more data.")

        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Prefer Live Scores",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.preferLiveScoresOverMetadata,
                    onCheckedChange = { viewModel.updatePreferLiveScores(it) },
                )
            }
        }
        FooterText("When a channel is carrying a live game, show the score instead of song metadata on the Now Playing screen and lock screen.")

        // ---- Podcast Episode Order (beads_adagio-59p.2.1) — only meaningful
        // with an Audiobookshelf account ----
        if (hasAudiobookshelfAccount) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Podcast Episode Order",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PodcastEpisodeOrder.entries.forEachIndexed { index, order ->
                    SegmentedButton(
                        selected = settings.podcastEpisodeSortOrder == order,
                        onClick = { viewModel.updatePodcastEpisodeSortOrder(order) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PodcastEpisodeOrder.entries.size,
                        ),
                    ) {
                        Text(text = order.displayName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            FooterText("How podcast episode lists are ordered. Also sets the direction auto-play walks through a show.")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SiriusXM Metadata Source",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SXMMetadataSource.entries.forEachIndexed { index, source ->
                SegmentedButton(
                    selected = settings.sxmMetadataSource == source,
                    onClick = { viewModel.updateSxmMetadataSource(source) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SXMMetadataSource.entries.size,
                    ),
                ) {
                    Text(text = source.displayName)
                }
            }
        }

        FooterText("Which service supplies SiriusXM now-playing song info. Switching re-matches your channels and resumes the current one.")

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Now Playing Refresh Interval",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppSettings.SXM_POLL_INTERVAL_OPTIONS.forEachIndexed { index, seconds ->
                SegmentedButton(
                    selected = AppSettings.clampSxmPollInterval(settings.sxmPollIntervalSeconds) == seconds,
                    onClick = { viewModel.updateSxmPollInterval(seconds) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppSettings.SXM_POLL_INTERVAL_OPTIONS.size,
                    ),
                ) {
                    Text(text = "$seconds")
                }
            }
        }

        FooterText("Seconds between SiriusXM now-playing refreshes for the tuned channel. Lower values show new songs sooner but use more data.")

        // ── Downloaded Music (E6) ────────────────────────────

        if (onNavigateToDownloads != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Downloaded Music",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                onClick = onNavigateToDownloads,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Manage Downloads",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Go to Downloaded Music",
                    )
                }
            }
            FooterText("View downloaded songs, see how much space they use, and free up storage.")

            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Offline Mode",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.offlineMode,
                        onCheckedChange = { viewModel.updateOfflineMode(it) },
                    )
                }
            }
            // Copy mirrors iOS DownloadsView footer.
            FooterText("When enabled, the Music tab shows only your downloaded tracks and won't make any network requests.")
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()

        // ── Advanced ─────────────────────────────────────────

        SectionHeader("Advanced")

        DebugLogsSection(viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your Data",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.exportMyData() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isExporting,
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text("Export My Data")
        }

        FooterText("Exports your accounts (without passwords), favorites, saved songs, playlists, groups, and settings as a JSON file.")

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { showDeleteStep1 = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete All My Data")
        }

        FooterText("Permanently deletes all app data including accounts, favorites, playlists, settings, cached images, and logs. This cannot be undone.")

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()

        // ── About ────────────────────────────────────────────

        SectionHeader("About")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("App", "AdagioStream")
                StatRow("Version", BuildConfig.VERSION_NAME)
                StatRow("Build", BuildConfig.VERSION_CODE.toString())
            }
        }

        if (onNavigateToPrivacyPolicy != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                onClick = onNavigateToPrivacyPolicy,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Privacy Policy",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Go to Privacy Policy",
                    )
                }
            }
        }

        if (onNavigateToLicenses != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                onClick = onNavigateToLicenses,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Open Source Licenses",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Go to Licenses",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showClearFavoritesDialog) {
        AlertDialog(
            onDismissRequest = { showClearFavoritesDialog = false },
            title = { Text("Clear All Favorites") },
            text = { Text("Are you sure you want to remove all $favoritesCount favorites? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllFavorites()
                        showClearFavoritesDialog = false
                    },
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearFavoritesDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteStep1) {
        AlertDialog(
            onDismissRequest = { showDeleteStep1 = false },
            title = { Text("Delete All Data?") },
            text = {
                Text("This will permanently erase all your accounts, favorites, saved songs, playlists, settings, cached images, and logs. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteStep1 = false
                        showDeleteStep2 = true
                    },
                ) {
                    Text("Continue", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteStep1 = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteStep2) {
        AlertDialog(
            onDismissRequest = { showDeleteStep2 = false },
            title = { Text("Are you sure?") },
            text = {
                Text("This is your last chance. All data will be permanently deleted and the app will reset to its initial state.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteStep2 = false
                        viewModel.deleteAllData()
                    },
                ) {
                    Text("Delete Everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteStep2 = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
}

@Composable
private fun FooterText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun DebugLogsSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logSize by viewModel.debugLogSize.collectAsStateWithLifecycle()
    val hasSubsonicAccount by viewModel.hasSubsonicAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showShareLogDialog by remember { mutableStateOf(false) }

    // ---- Android Auto Browse Order (baw.7.1) — only meaningful with a Subsonic account ----
    if (hasSubsonicAccount) {
        Text(
            text = "Android Auto Browse Order",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AutoSourceOrder.entries.forEachIndexed { index, order ->
                SegmentedButton(
                    selected = settings.autoSourceOrder == order,
                    onClick = { viewModel.updateAutoSourceOrder(order) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AutoSourceOrder.entries.size,
                    ),
                ) {
                    Text(text = order.displayName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        FooterText("Choose whether Channels or Music appears first in the Android Auto browse tree.")
        Spacer(modifier = Modifier.height(16.dp))
    }

    // ---- Debug Logs ----
    Text(
        text = "Debug Logs",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Logging",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Log size: $logSize",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.debugLoggingEnabled,
                    onCheckedChange = { viewModel.updateDebugLogging(it) },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showShareLogDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = logSize != "0 KB",
                ) {
                    Text("Share Log")
                }
                Button(
                    onClick = {
                        viewModel.clearDebugLogs()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    enabled = logSize != "0 KB",
                ) {
                    Text("Clear Logs")
                }
            }
        }
    }

    viewModel.refreshDebugLogSize()

    if (showShareLogDialog) {
        AlertDialog(
            onDismissRequest = { showShareLogDialog = false },
            title = { Text("Share Debug Log") },
            text = { Text("Debug logs may contain sensitive information including server URLs, account details, and stream addresses. Only share with trusted recipients.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showShareLogDialog = false
                        val file = DebugLogger.logFile()
                        if (file != null) {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Debug Log"))
                            } catch (e: Exception) {
                                Log.e("SettingsScreen", "Failed to share log", e)
                                Toast.makeText(context, "Unable to share log file", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareLogDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartupStreamPicker(
    selectedId: String?,
    favorites: List<Channel>,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = favorites.find { it.id == selectedId }?.name ?: "None"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            favorites.forEach { channel ->
                DropdownMenuItem(
                    text = { Text(channel.name) },
                    onClick = {
                        onSelected(channel.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SortPrefixEditor(
    prefixes: List<String>,
    onPrefixesChanged: (List<String>) -> Unit,
) {
    var newPrefix by remember { mutableStateOf("") }

    Column {
        prefixes.forEachIndexed { index, prefix ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "\"$prefix\"",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    onPrefixesChanged(prefixes.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newPrefix,
                onValueChange = { newPrefix = it },
                placeholder = { Text("New prefix...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (newPrefix.isNotBlank()) {
                        onPrefixesChanged(prefixes + newPrefix)
                        newPrefix = ""
                    }
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    }
}
