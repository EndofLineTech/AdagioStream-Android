package com.adagiostream.android.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers beads_adagio-59p.3.1/.2: the SXM metadata source must default to
 * StellarTunerLog (and fall back there on garbage without nuking the rest of
 * the settings decode), and the poll interval must clamp stored garbage into
 * the 10–45s range.
 */
class AppSettingsSXMTest {

    // Same leniency as the app's DI-provided Json
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Source decoding

    @Test
    fun `source defaults to StellarTunerLog when unset`() {
        assertEquals(SXMMetadataSource.STELLARTUNERLOG, json.decodeFromString<AppSettings>("{}").sxmMetadataSource)
    }

    @Test
    fun `persisted source choices decode`() {
        assertEquals(
            SXMMetadataSource.XMPLAYLIST,
            json.decodeFromString<AppSettings>("""{"sxmMetadataSource":"xmplaylist"}""").sxmMetadataSource,
        )
        assertEquals(
            SXMMetadataSource.STELLARTUNERLOG,
            json.decodeFromString<AppSettings>("""{"sxmMetadataSource":"stellartunerlog"}""").sxmMetadataSource,
        )
    }

    @Test
    fun `garbage source falls back to StellarTunerLog without failing the whole settings decode`() {
        val settings = json.decodeFromString<AppSettings>(
            """{"sxmMetadataSource":"windows95","debugLoggingEnabled":true}""",
        )
        assertEquals(SXMMetadataSource.STELLARTUNERLOG, settings.sxmMetadataSource)
        // The rest of the settings survive — a bad source must not reset everything
        assertEquals(true, settings.debugLoggingEnabled)
    }

    @Test
    fun `source round-trips through its serial name`() {
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings(sxmMetadataSource = SXMMetadataSource.XMPLAYLIST))
        assertEquals(
            SXMMetadataSource.XMPLAYLIST,
            json.decodeFromString<AppSettings>(encoded).sxmMetadataSource,
        )
    }

    // MARK: - Poll interval clamping

    @Test
    fun `poll interval clamps stored garbage into range`() {
        assertEquals(10, AppSettings.clampSxmPollInterval(-5))
        assertEquals(10, AppSettings.clampSxmPollInterval(9))
        assertEquals(10, AppSettings.clampSxmPollInterval(10))
        assertEquals(30, AppSettings.clampSxmPollInterval(30))
        assertEquals(45, AppSettings.clampSxmPollInterval(45))
        assertEquals(45, AppSettings.clampSxmPollInterval(9000))
    }

    @Test
    fun `poll interval defaults to 30s`() {
        assertEquals(30, AppSettings().sxmPollIntervalSeconds)
        assertEquals(AppSettings.SXM_POLL_INTERVAL_DEFAULT, AppSettings().sxmPollIntervalSeconds)
    }
}
