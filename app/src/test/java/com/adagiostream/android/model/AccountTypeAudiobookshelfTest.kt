package com.adagiostream.android.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Audiobookshelf variant of AccountType serialization round-trip (59p.1.3). */
class AccountTypeAudiobookshelfTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Audiobookshelf AccountType round-trips through JSON serialization`() {
        val original = AccountType.Audiobookshelf(
            host = "https://abs.example.com",
            username = "alice",
            accessToken = "acc-1",
            refreshToken = "ref-1",
        )
        val encoded = json.encodeToString<AccountType>(original)
        val decoded = json.decodeFromString<AccountType>(encoded)

        assertTrue("Decoded type must be Audiobookshelf", decoded is AccountType.Audiobookshelf)
        val abs = decoded as AccountType.Audiobookshelf
        assertEquals("https://abs.example.com", abs.host)
        assertEquals("alice", abs.username)
        assertEquals("acc-1", abs.accessToken)
        assertEquals("ref-1", abs.refreshToken)
    }

    @Test
    fun `Audiobookshelf AccountType serializes with SerialName audiobookshelf`() {
        val encoded = json.encodeToString<AccountType>(
            AccountType.Audiobookshelf(host = "https://abs.local"),
        )
        assertTrue(
            "Encoded JSON must contain type discriminator \"audiobookshelf\"",
            encoded.contains("\"audiobookshelf\""),
        )
    }

    @Test
    fun `Audiobookshelf decodes with null username and tokens for future SSO accounts`() {
        val decoded = json.decodeFromString<AccountType>(
            """{"type":"audiobookshelf","host":"https://abs.example.com"}""",
        )

        assertTrue(decoded is AccountType.Audiobookshelf)
        val abs = decoded as AccountType.Audiobookshelf
        assertNull(abs.username)
        assertNull(abs.accessToken)
        assertNull(abs.refreshToken)
    }

    @Test
    fun `Account containing Audiobookshelf type round-trips correctly`() {
        val account = Account(
            id = "acc-1",
            name = "My ABS",
            type = AccountType.Audiobookshelf(
                host = "https://abs.home.lab",
                username = "user",
                accessToken = "a",
                refreshToken = "r",
            ),
        )
        val encoded = json.encodeToString(account)
        val decoded = json.decodeFromString<Account>(encoded)

        assertEquals("acc-1", decoded.id)
        assertEquals("My ABS", decoded.name)
        assertTrue(decoded.type is AccountType.Audiobookshelf)
        assertTrue(decoded.isEnabled)
    }
}
