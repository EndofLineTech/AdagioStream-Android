package com.adagiostream.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the bottom-nav tab composition (beads_adagio-15x.2/.15x.3): no
 * Favorites tab (pinned atop Channels instead), Library always present
 * (no more musicTabEnabled gate), and the final Live · Library · Loved ·
 * Custom M3Us · Settings order/labels.
 */
class ScreenTest {

    @Test
    fun `bottomNavItems has no Favorites tab`() {
        assertFalse(bottomNavItems.contains(Screen.Favorites))
    }

    @Test
    fun `bottomNavItems always contains Library`() {
        assertTrue(bottomNavItems.contains(Screen.Music))
    }

    @Test
    fun `bottomNavItems order and labels match the final bar`() {
        assertEquals(
            listOf("Live", "Library", "Loved", "Custom M3Us", "Settings"),
            bottomNavItems.map { it.label },
        )
    }

    @Test
    fun `Channels and MyM3Us route strings are unchanged despite label renames`() {
        // Route stability matters for deep links / saved nav state.
        assertEquals("channels", Screen.Channels.route)
        assertEquals("my_m3us", Screen.MyM3Us.route)
        assertEquals("music", Screen.Music.route)
    }
}
