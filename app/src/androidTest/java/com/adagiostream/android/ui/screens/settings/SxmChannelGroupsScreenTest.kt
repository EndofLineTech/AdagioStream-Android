package com.adagiostream.android.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.adagiostream.android.service.account.RawChannelGroupInventory
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SxmChannelGroupsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsCardOpensTheGroupDestination() {
        var opened by mutableStateOf(false)
        val inventory = MutableStateFlow<RawChannelGroupInventory>(RawChannelGroupInventory.Loading)
        val selection = MutableStateFlow<Set<String>?>(setOf("SiriusXM"))
        val saveError = MutableStateFlow<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                if (opened) {
                    SxmChannelGroupsRoute(inventory, selection, saveError)
                } else {
                    SxmChannelGroupsSettingsCard(setOf("SiriusXM")) { opened = true }
                }
            }
        }

        composeRule.onNodeWithText("SiriusXM Channel Groups").performClick()

        composeRule.onNodeWithText("Loading the complete raw channel-group inventory...").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(true, opened) }
    }

    @Test
    fun loadingNoAccountsAndNoGroupsRenderDistinctStates() {
        var state by mutableStateOf<SxmGroupEditorState>(SxmGroupEditorState.Loading)
        composeRule.setContent { MaterialTheme { SxmChannelGroupsContent(state) } }
        composeRule.onNodeWithText("Loading the complete raw channel-group inventory...").assertIsDisplayed()

        composeRule.runOnIdle { state = SxmGroupEditorState.NoAccounts }
        composeRule.onNodeWithText("No channel accounts").assertIsDisplayed()

        composeRule.runOnIdle { state = SxmGroupEditorState.NoGroups }
        composeRule.onNodeWithText("No channel groups").assertIsDisplayed()
    }

    @Test
    fun searchCountsSelectionAndImmediateToggleAreRenderedAndInteractive() {
        val inventory = MutableStateFlow<RawChannelGroupInventory>(
            RawChannelGroupInventory.Complete(mapOf("SiriusXM" to 12, "Sports" to 3)),
        )
        val selection = MutableStateFlow<Set<String>?>(setOf("SiriusXM"))
        val saveError = MutableStateFlow<String?>(null)
        val persisted = mutableListOf<Set<String>>()
        composeRule.setContent {
            MaterialTheme {
                SxmChannelGroupsRoute(
                    inventory = inventory,
                    selection = selection,
                    saveError = saveError,
                    onToggle = { group ->
                        val updated = selection.value.orEmpty() - group
                        selection.value = updated
                        persisted += updated
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("SiriusXM, 12 channels").assertIsOn().performClick()
        composeRule.onNodeWithContentDescription("SiriusXM, 12 channels").assertIsOff()
        composeRule.runOnIdle { assertEquals(listOf(emptySet<String>()), persisted) }
        composeRule.onNodeWithText("12 channels").assertIsDisplayed()

        composeRule.onNodeWithText("Search channel groups").performTextInput("sport")
        composeRule.onNodeWithText("Sports").assertIsDisplayed()
        composeRule.onNodeWithText("SiriusXM").assertDoesNotExist()
    }

    @Test
    fun unavailableSelectionCanBeDeselectedWithNonColorStateSemantics() {
        var toggled: String? = null
        val state = buildSxmGroupEditorState(
            RawChannelGroupInventory.Complete(mapOf("Available" to 1)),
            setOf("Unavailable"),
        )
        composeRule.setContent {
            MaterialTheme { SxmChannelGroupsContent(state, onToggle = { toggled = it }) }
        }

        composeRule.onNodeWithContentDescription("Unavailable, Unavailable")
            .assertIsOn()
            .assertTextContains("Unavailable")
            .performClick()
        composeRule.runOnIdle { assertEquals("Unavailable", toggled) }
    }

    @Test
    fun partialAndMigrationSaveFailuresRenderAndExerciseSeparateRetries() {
        val inventory = MutableStateFlow<RawChannelGroupInventory>(
            RawChannelGroupInventory.PartialFailure(mapOf("Loaded" to 1), "Provider failed"),
        )
        val selection = MutableStateFlow<Set<String>?>(null)
        val saveError = MutableStateFlow<String?>("Could not save selection")
        var migrationRetries = 0
        var inventoryRetries = 0
        composeRule.setContent {
            MaterialTheme {
                SxmChannelGroupsRoute(
                    inventory = inventory,
                    selection = selection,
                    saveError = saveError,
                    onRetryMigration = {
                        migrationRetries += 1
                        saveError.value = null
                    },
                    onRetryInventory = {
                        inventoryRetries += 1
                        inventory.value = RawChannelGroupInventory.Complete(mapOf("Loaded" to 1))
                    },
                )
            }
        }

        composeRule.onNodeWithText("Could not save selection").assertIsDisplayed()
        composeRule.onNodeWithText("Some accounts could not be loaded.", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Retry")[0].performClick()
        composeRule.onNodeWithText("Could not save selection").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Some accounts could not be loaded.", substring = true).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, migrationRetries)
            assertEquals(1, inventoryRetries)
        }
    }
}
