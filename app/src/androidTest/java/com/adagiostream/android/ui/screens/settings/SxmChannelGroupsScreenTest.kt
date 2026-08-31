package com.adagiostream.android.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.adagiostream.android.service.account.RawChannelGroupInventory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SxmChannelGroupsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsCardOpensTheGroupDestination() {
        var opened = false
        composeRule.setContent {
            MaterialTheme {
                SxmChannelGroupsSettingsCard(setOf("SiriusXM")) { opened = true }
            }
        }

        composeRule.onNodeWithText("SiriusXM Channel Groups").performClick()

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
        val inventory = RawChannelGroupInventory.Complete(mapOf("SiriusXM" to 12, "Sports" to 3))
        var query by mutableStateOf("")
        var toggled: String? = null
        composeRule.setContent {
            MaterialTheme {
                SxmChannelGroupsContent(
                    state = buildSxmGroupEditorState(inventory, setOf("SiriusXM"), query),
                    query = query,
                    onQueryChange = { query = it },
                    onToggle = { toggled = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("SiriusXM, 12 channels").assertIsOn().performClick()
        composeRule.runOnIdle { assertEquals("SiriusXM", toggled) }
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
    fun partialAndSaveFailuresRenderSeparateActions() {
        val state = buildSxmGroupEditorState(
            RawChannelGroupInventory.PartialFailure(mapOf("Loaded" to 1), "Provider failed"),
            setOf("Loaded"),
            saveError = "Could not save selection",
        )
        composeRule.setContent { MaterialTheme { SxmChannelGroupsContent(state) } }

        composeRule.onNodeWithText("Could not save selection").assertIsDisplayed()
        composeRule.onNodeWithText("Some accounts could not be loaded.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }
}
