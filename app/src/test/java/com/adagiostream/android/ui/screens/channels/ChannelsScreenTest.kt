package com.adagiostream.android.ui.screens.channels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned-favorites visibility rule (beads_adagio-15x.1): the section is
 * hidden while a search filter is active, and hidden when there are no
 * favorites to show.
 */
class ChannelsScreenTest {

    @Test
    fun `shown with no search and at least one favorite`() {
        assertTrue(shouldShowPinnedFavorites(searchQuery = "", favoritesCount = 1))
    }

    @Test
    fun `hidden while a search query is active`() {
        assertFalse(shouldShowPinnedFavorites(searchQuery = "espn", favoritesCount = 3))
    }

    @Test
    fun `hidden when there are no favorites`() {
        assertFalse(shouldShowPinnedFavorites(searchQuery = "", favoritesCount = 0))
    }

    @Test
    fun `blank (whitespace-only) search still counts as no search`() {
        assertTrue(shouldShowPinnedFavorites(searchQuery = "   ", favoritesCount = 1))
    }
}
