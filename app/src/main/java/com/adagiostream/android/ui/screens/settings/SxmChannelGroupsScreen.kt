package com.adagiostream.android.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.service.account.RawChannelGroupInventory
import java.util.Locale

internal data class SxmGroupOption(
    val name: String,
    val channelCount: Int?,
    val selected: Boolean,
)

internal sealed interface SxmGroupEditorState {
    data object Loading : SxmGroupEditorState
    data object NoAccounts : SxmGroupEditorState
    data object NoGroups : SxmGroupEditorState
    data class Groups(
        val available: List<SxmGroupOption>,
        val unavailable: List<SxmGroupOption>,
        val inventoryWarning: String?,
        val saveError: String?,
        val editable: Boolean = true,
    ) : SxmGroupEditorState
}

internal fun sxmSelectionSummary(selection: Set<String>?): String = when (selection?.size) {
    null -> "Loading..."
    0 -> "None"
    1 -> "1 selected"
    else -> "${selection.size} selected"
}

internal fun buildSxmGroupEditorState(
    inventory: RawChannelGroupInventory,
    selected: Set<String>?,
    query: String = "",
    saveError: String? = null,
): SxmGroupEditorState {
    if (inventory is RawChannelGroupInventory.Loading) {
        return SxmGroupEditorState.Loading
    }
    if (inventory is RawChannelGroupInventory.NoAccounts && selected.orEmpty().isEmpty()) {
        return SxmGroupEditorState.NoAccounts
    }
    if (selected == null && inventory is RawChannelGroupInventory.Complete && saveError == null) {
        return SxmGroupEditorState.Loading
    }

    val counts = when (inventory) {
        is RawChannelGroupInventory.Complete -> inventory.groupCounts
        is RawChannelGroupInventory.PartialFailure -> inventory.groupCounts
        RawChannelGroupInventory.Loading, RawChannelGroupInventory.NoAccounts -> emptyMap()
    }
    val initializedSelection = selected.orEmpty()
    if (counts.isEmpty() && inventory is RawChannelGroupInventory.Complete && initializedSelection.isEmpty()) {
        return SxmGroupEditorState.NoGroups
    }

    val matchesQuery: (String) -> Boolean = { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    val comparator = compareBy<SxmGroupOption> { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.name }
    val available = counts.map { (name, count) ->
        SxmGroupOption(name, count, name in initializedSelection)
    }.filter { matchesQuery(it.name) }.sortedWith(comparator)
    val unavailable = (initializedSelection - counts.keys).map { name ->
        SxmGroupOption(name, null, selected = true)
    }.filter { matchesQuery(it.name) }.sortedWith(comparator)

    return SxmGroupEditorState.Groups(
        available = available,
        unavailable = unavailable,
        inventoryWarning = when (inventory) {
            is RawChannelGroupInventory.PartialFailure ->
                "Some accounts could not be loaded. Available groups may be incomplete.\n${inventory.message}"
            RawChannelGroupInventory.NoAccounts ->
                "No channel accounts are currently available. Selected names are kept for removal."
            else -> null
        },
        saveError = saveError,
        editable = selected != null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SxmChannelGroupsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val inventory by viewModel.rawChannelGroupInventory.collectAsStateWithLifecycle()
    val selection by viewModel.sxmChannelGroups.collectAsStateWithLifecycle()
    val saveError by viewModel.sxmSelectionSaveError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val state = buildSxmGroupEditorState(inventory, selection, query, saveError)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SiriusXM Channel Groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Choose groups whose channels should be matched with SiriusXM now-playing data. A match is not guaranteed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Text(
                    "This device-only selection does not change which channels or groups are visible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is SxmGroupEditorState.Groups) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search channel groups") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.saveError?.let { message ->
                    item {
                        val migrationFailed = selection == null
                        ErrorCard(message, actionLabel = if (migrationFailed) "Retry" else "Dismiss") {
                            if (migrationFailed) {
                                viewModel.retrySxmSelectionMigration()
                            } else {
                                viewModel.clearSxmSelectionSaveError()
                            }
                        }
                    }
                }
                state.inventoryWarning?.let { message ->
                    item {
                        ErrorCard(message, actionLabel = "Retry") {
                            viewModel.retrySxmChannelGroupInventory()
                        }
                    }
                }
                if (state.available.isNotEmpty()) {
                    item { Text("Available Groups", style = MaterialTheme.typography.titleMedium) }
                    items(state.available, key = { "available:${it.name}" }) { option ->
                        SxmGroupRow(option, enabled = state.editable) {
                            viewModel.toggleSxmChannelGroup(option.name)
                        }
                    }
                }
                if (state.unavailable.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Unavailable Selected Groups", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "These names are not in the current inventory. Deselect them to remove them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.unavailable, key = { "unavailable:${it.name}" }) { option ->
                        SxmGroupRow(option, enabled = state.editable) {
                            viewModel.toggleSxmChannelGroup(option.name)
                        }
                    }
                }
                if (state.available.isEmpty() && state.unavailable.isEmpty()) {
                    item { Text("No channel groups match your search.") }
                }
            } else {
                item {
                    when (state) {
                        SxmGroupEditorState.Loading -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Loading the complete raw channel-group inventory...")
                        }
                        SxmGroupEditorState.NoAccounts -> EmptyInventory(
                            "No channel accounts",
                            "Add or enable an M3U or Xtream Codes account to load channel groups.",
                            viewModel::retrySxmChannelGroupInventory,
                        )
                        SxmGroupEditorState.NoGroups -> EmptyInventory(
                            "No channel groups",
                            "The complete inventory loaded, but it contains no groups.",
                            viewModel::retrySxmChannelGroupInventory,
                        )
                        is SxmGroupEditorState.Groups -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SxmGroupRow(option: SxmGroupOption, enabled: Boolean, onToggle: () -> Unit) {
    val countText = option.channelCount?.let { "$it ${if (it == 1) "channel" else "channels"}" } ?: "Unavailable"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics {
                role = Role.Checkbox
                contentDescription = "${option.name}, $countText"
                stateDescription = if (option.selected) "Selected" else "Not selected"
            }
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = option.selected,
                enabled = enabled,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(option.name, style = MaterialTheme.typography.bodyLarge)
                Text(countText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, actionLabel: String, action: () -> Unit) {
    Card(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = action, modifier = Modifier.align(Alignment.End)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptyInventory(title: String, message: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message)
        TextButton(onClick = retry) { Text("Retry") }
    }
}
