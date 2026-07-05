package com.adagiostream.android.model

import com.adagiostream.android.service.player.RepeatMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(AutoSourceOrder.STREAMING_FIRST, defaults.autoSourceOrder)
    }

    @Test
    fun `offlineMode defaults false and survives a JSON round-trip`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(false, AppSettings().offlineMode)

        val decoded = json.decodeFromString<AppSettings>(
            json.encodeToString(AppSettings.serializer(), AppSettings(offlineMode = true)),
        )
        assertEquals(true, decoded.offlineMode)

        // Settings blobs written before baw.12 have no offlineMode key — must decode to false.
        assertEquals(false, json.decodeFromString<AppSettings>("{}").offlineMode)
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

    // ---- shuffle/repeat persistence round-trip (baw.9.4 / baw.16) -----------

    @Test
    fun `AppSettings round-trips shuffleEnabled and repeatMode through JSON`() {
        val json = Json { encodeDefaults = true }
        val settings = AppSettings(shuffleEnabled = true, repeatMode = RepeatMode.All)

        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(settings, decoded)
        // Pin the on-disk key/value shape — a silent rename here would strand
        // every user's persisted shuffle/repeat state on the next app read.
        assertTrue(
            "expected shuffleEnabled:true in persisted JSON but was: $encoded",
            encoded.contains("\"shuffleEnabled\":true"),
        )
        assertTrue(
            "expected repeatMode:All in persisted JSON but was: $encoded",
            encoded.contains("\"repeatMode\":\"All\""),
        )
    }

    @Test
    fun `AppSettings defaults shuffleEnabled false and repeatMode Off`() {
        val defaults = AppSettings()
        assertEquals(false, defaults.shuffleEnabled)
        assertEquals(RepeatMode.Off, defaults.repeatMode)
    }
}
