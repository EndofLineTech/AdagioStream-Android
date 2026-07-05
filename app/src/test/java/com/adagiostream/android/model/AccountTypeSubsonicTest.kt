package com.adagiostream.android.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Subsonic variant of AccountType serialization round-trip. */
class AccountTypeSubsonicTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Round-trip serialization
    // -------------------------------------------------------------------------

    @Test
    fun `Subsonic AccountType round-trips through JSON serialization`() {
        val original = AccountType.Subsonic(
            host = "https://music.example.com",
            username = "alice",
            password = "sesame",
        )
        val encoded = json.encodeToString<AccountType>(original)
        val decoded = json.decodeFromString<AccountType>(encoded)

        assertTrue("Decoded type must be Subsonic", decoded is AccountType.Subsonic)
        val subsonic = decoded as AccountType.Subsonic
        assertEquals("https://music.example.com", subsonic.host)
        assertEquals("alice", subsonic.username)
        assertEquals("sesame", subsonic.password)
    }

    @Test
    fun `Subsonic AccountType serializes with SerialName subsonic`() {
        val original = AccountType.Subsonic(
            host = "https://navidrome.local",
            username = "bob",
            password = "hunter2",
        )
        val encoded = json.encodeToString<AccountType>(original)
        assertTrue(
            "Encoded JSON must contain type discriminator \"subsonic\"",
            encoded.contains("\"subsonic\""),
        )
    }

    @Test
    fun `Account containing Subsonic type round-trips correctly`() {
        val account = Account(
            id = "acc-1",
            name = "My Navidrome",
            type = AccountType.Subsonic(
                host = "https://music.home.lab",
                username = "user",
                password = "pass",
            ),
        )
        val encoded = json.encodeToString(account)
        val decoded = json.decodeFromString<Account>(encoded)

        assertEquals("acc-1", decoded.id)
        assertEquals("My Navidrome", decoded.name)
        assertTrue(decoded.type is AccountType.Subsonic)
        val subsonic = decoded.type as AccountType.Subsonic
        assertEquals("https://music.home.lab", subsonic.host)
        assertEquals("user", subsonic.username)
        assertEquals("pass", subsonic.password)
        assertTrue(decoded.isEnabled)
    }

    // -------------------------------------------------------------------------
    // Ensure existing types (M3U, XtreamCodes) are unaffected
    // -------------------------------------------------------------------------

    @Test
    fun `M3U AccountType still round-trips after Subsonic addition`() {
        val original = AccountType.M3U(url = "http://example.com/playlist.m3u")
        val encoded = json.encodeToString<AccountType>(original)
        val decoded = json.decodeFromString<AccountType>(encoded)
        assertTrue(decoded is AccountType.M3U)
        assertEquals("http://example.com/playlist.m3u", (decoded as AccountType.M3U).url)
    }

    @Test
    fun `XtreamCodes AccountType still round-trips after Subsonic addition`() {
        val original = AccountType.XtreamCodes(
            host = "http://xtream.example.com",
            username = "xuser",
            password = "xpass",
        )
        val encoded = json.encodeToString<AccountType>(original)
        val decoded = json.decodeFromString<AccountType>(encoded)
        assertTrue(decoded is AccountType.XtreamCodes)
    }

    // -------------------------------------------------------------------------
    // Subsonic is its own sealed variant — verified at compile time by
    // the exhaustive when branches in AccountManager / AddAccountViewModel.
    // -------------------------------------------------------------------------

    @Test
    fun `Subsonic constructs with host username and password`() {
        val subsonic = AccountType.Subsonic(host = "https://h", username = "u", password = "p")
        assertEquals("https://h", subsonic.host)
        assertEquals("u", subsonic.username)
        assertEquals("p", subsonic.password)
    }
}
