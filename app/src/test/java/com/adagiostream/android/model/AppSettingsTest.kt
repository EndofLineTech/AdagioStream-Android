package com.adagiostream.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `TextSizeMode XS has scale factor 0_8`() {
        assertEquals(0.8f, TextSizeMode.XS.scaleFactor)
    }

    @Test
    fun `TextSizeMode M has scale factor 1_0`() {
        assertEquals(1.0f, TextSizeMode.M.scaleFactor)
    }

    @Test
    fun `TextSizeMode ACCESSIBILITY_3 has scale factor 1_8`() {
        assertEquals(1.8f, TextSizeMode.ACCESSIBILITY_3.scaleFactor)
    }

    @Test
    fun `SortMode displayNames are correct`() {
        assertEquals("Account Order", SortMode.ACCOUNT_ORDER.displayName)
        assertEquals("Natural", SortMode.NATURAL.displayName)
        assertEquals("A-Z", SortMode.ALPHABETICAL.displayName)
    }

    @Test
    fun `AppSettings defaults are sensible`() {
        val defaults = AppSettings()
        assertEquals(10, defaults.bufferDurationSeconds)
        assertEquals(AppearanceMode.SYSTEM, defaults.appearanceMode)
        assertEquals(TextSizeMode.M, defaults.textSizeMode)
        assertEquals(SortMode.ALPHABETICAL, defaults.sortMode)
        assertEquals(SortMode.ALPHABETICAL, defaults.groupSortMode)
    }

    @Test
    fun `TextSizeMode values are ordered by scale`() {
        val factors = TextSizeMode.entries.map { it.scaleFactor }
        assertEquals(factors.sorted(), factors)
    }

    @Test
    fun `TextSizeMode displayNames are all non-empty`() {
        TextSizeMode.entries.forEach { mode ->
            assert(mode.displayName.isNotBlank()) { "${mode.name} has blank displayName" }
        }
    }
}
