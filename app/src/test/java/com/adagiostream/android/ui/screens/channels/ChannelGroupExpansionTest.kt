package com.adagiostream.android.ui.screens.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collapsible channel-group rules (beads_adagio-59p.4.1, iOS parity:
 * `ChannelListView`): everything collapsed by default, Favorites section
 * first under a NUL-sentinel key, search forces sections open, and the
 * expand-set round-trips through toggles.
 */
class ChannelGroupExpansionTest {

    @Test
    fun `all sections collapsed by default`() {
        assertFalse(isSectionExpanded("Rock", searchQuery = "", expandedKeys = emptySet()))
        assertFalse(isSectionExpanded(FAVORITES_EXPAND_KEY, searchQuery = "", expandedKeys = emptySet()))
    }

    @Test
    fun `stored key renders expanded`() {
        assertTrue(isSectionExpanded("Rock", searchQuery = "", expandedKeys = setOf("Rock")))
        assertFalse(isSectionExpanded("Jazz", searchQuery = "", expandedKeys = setOf("Rock")))
    }

    @Test
    fun `search forces every section expanded regardless of stored state`() {
        assertTrue(isSectionExpanded("Rock", searchQuery = "espn", expandedKeys = emptySet()))
        assertTrue(isSectionExpanded(FAVORITES_EXPAND_KEY, searchQuery = "espn", expandedKeys = emptySet()))
    }

    @Test
    fun `blank (whitespace-only) search does not force expansion`() {
        assertFalse(isSectionExpanded("Rock", searchQuery = "   ", expandedKeys = emptySet()))
    }

    @Test
    fun `favorites sentinel cannot collide with a real group named Favorites`() {
        assertNotEquals("Favorites", FAVORITES_EXPAND_KEY)
        // Expanding the real group must not expand the Favorites section, and vice versa.
        assertFalse(isSectionExpanded(FAVORITES_EXPAND_KEY, searchQuery = "", expandedKeys = setOf("Favorites")))
        assertFalse(isSectionExpanded("Favorites", searchQuery = "", expandedKeys = setOf(FAVORITES_EXPAND_KEY)))
    }

    @Test
    fun `favorites section orders first, ahead of a real Favorites group`() {
        val keys = allSectionKeys(listOf("Favorites", "Rock", "Jazz"))
        assertEquals(listOf(FAVORITES_EXPAND_KEY, "Favorites", "Rock", "Jazz"), keys)
    }

    @Test
    fun `toggle round-trips back to the original set`() {
        val start = setOf("Rock")
        val expanded = toggleExpandKey(start, "Jazz")
        assertEquals(setOf("Rock", "Jazz"), expanded)
        assertEquals(start, toggleExpandKey(expanded, "Jazz"))
    }
}
