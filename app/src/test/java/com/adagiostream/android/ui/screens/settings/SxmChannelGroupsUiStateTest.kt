package com.adagiostream.android.ui.screens.settings

import com.adagiostream.android.service.account.RawChannelGroupInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SxmChannelGroupsUiStateTest {
    @Test
    fun `summary handles zero one and many selections`() {
        assertEquals("None", sxmSelectionSummary(emptySet()))
        assertEquals("1 selected", sxmSelectionSummary(setOf("One")))
        assertEquals("3 selected", sxmSelectionSummary(setOf("One", "Two", "Three")))
    }

    @Test
    fun `loading no-account and no-groups are distinct`() {
        assertTrue(buildSxmGroupEditorState(RawChannelGroupInventory.Loading, emptySet()) is SxmGroupEditorState.Loading)
        assertTrue(buildSxmGroupEditorState(RawChannelGroupInventory.NoAccounts, emptySet()) is SxmGroupEditorState.NoAccounts)
        assertTrue(
            buildSxmGroupEditorState(
                RawChannelGroupInventory.Complete(emptyMap()),
                emptySet(),
            ) is SxmGroupEditorState.NoGroups,
        )
    }

    @Test
    fun `available names are searchable alphabetically stable and include counts`() {
        val state = buildSxmGroupEditorState(
            inventory = RawChannelGroupInventory.Complete(mapOf("Zulu" to 2, "alpha" to 4, "Alpha 2" to 1)),
            selected = setOf("Zulu"),
            query = "a",
        ) as SxmGroupEditorState.Groups

        assertEquals(listOf("alpha", "Alpha 2"), state.available.map { it.name })
        assertEquals(listOf(4, 1), state.available.map { it.channelCount })
    }

    @Test
    fun `unavailable selections stay visible and removable`() {
        val state = buildSxmGroupEditorState(
            inventory = RawChannelGroupInventory.Complete(mapOf("Present" to 3)),
            selected = setOf("Present", "Renamed Away"),
        ) as SxmGroupEditorState.Groups

        assertEquals(listOf("Renamed Away"), state.unavailable.map { it.name })
        assertTrue(state.unavailable.single().selected)
    }

    @Test
    fun `unavailable selections remain manageable with no channel accounts`() {
        val state = buildSxmGroupEditorState(
            inventory = RawChannelGroupInventory.NoAccounts,
            selected = setOf("Remembered Group"),
        ) as SxmGroupEditorState.Groups

        assertEquals(listOf("Remembered Group"), state.unavailable.map { it.name })
        assertTrue(state.inventoryWarning?.contains("No channel accounts") == true)
        assertTrue(state.editable)
    }

    @Test
    fun `partial inventory and save failure remain explicit`() {
        val state = buildSxmGroupEditorState(
            inventory = RawChannelGroupInventory.PartialFailure(
                groupCounts = mapOf("Loaded" to 1),
                message = "One provider failed",
            ),
            selected = setOf("Loaded"),
            saveError = "Could not save selection",
        ) as SxmGroupEditorState.Groups

        assertTrue(state.inventoryWarning?.contains("One provider failed") == true)
        assertEquals("Could not save selection", state.saveError)
    }

    @Test
    fun `migration save failure is visible instead of looking like loading`() {
        val state = buildSxmGroupEditorState(
            inventory = RawChannelGroupInventory.Complete(mapOf("SiriusXM" to 10)),
            selected = null,
            saveError = "Could not save migration",
        ) as SxmGroupEditorState.Groups

        assertEquals("Could not save migration", state.saveError)
        assertEquals(false, state.editable)
    }
}
