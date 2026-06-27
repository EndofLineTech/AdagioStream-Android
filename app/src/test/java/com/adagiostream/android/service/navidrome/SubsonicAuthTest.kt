package com.adagiostream.android.service.navidrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicAuthTest {

    // -------------------------------------------------------------------------
    // Spec test vector (canonical Subsonic protocol example)
    // Source: SubsonicAuthTests.swift — password "sesame" + salt "c19b2d"
    //         => token "26719a1196d2a940705a59634eb18eab"
    // -------------------------------------------------------------------------

    @Test
    fun `spec test vector MD5 matches canonical Subsonic example`() {
        val token = SubsonicAuth.md5Token(password = "sesame", salt = "c19b2d")
        assertEquals(
            "MD5(password+salt) must match the canonical Subsonic spec example",
            "26719a1196d2a940705a59634eb18eab",
            token,
        )
    }

    // -------------------------------------------------------------------------
    // queryParams returns all six required fields
    // -------------------------------------------------------------------------

    @Test
    fun `queryParams with fixed salt returns all six required fields`() {
        val auth = SubsonicAuth(username = "alice", password = "sesame")
        val params = auth.queryParams(salt = "c19b2d")

        assertEquals("alice", params["u"])
        assertEquals("26719a1196d2a940705a59634eb18eab", params["t"])
        assertEquals("c19b2d", params["s"])
        assertEquals(SubsonicAuth.CLIENT_NAME, params["c"])
        assertEquals(SubsonicAuth.API_VERSION, params["v"])
        assertEquals("json", params["f"])
        assertEquals(6, params.size)
    }

    @Test
    fun `CLIENT_NAME is AdagioStream`() {
        assertEquals("AdagioStream", SubsonicAuth.CLIENT_NAME)
    }

    @Test
    fun `API_VERSION is 1 16 1`() {
        assertEquals("1.16.1", SubsonicAuth.API_VERSION)
    }

    // -------------------------------------------------------------------------
    // Salt randomness and length
    // -------------------------------------------------------------------------

    @Test
    fun `auto-generated salt is at least 16 hex chars (8 bytes)`() {
        val auth = SubsonicAuth(username = "eve", password = "pass")
        val params = auth.queryParams()
        val salt = params["s"] ?: ""
        assertTrue(
            "Salt must be at least 16 hex characters (≥8 bytes), was: ${salt.length}",
            salt.length >= 16,
        )
    }

    @Test
    fun `two calls produce different salts`() {
        val auth = SubsonicAuth(username = "frank", password = "pass")
        val params1 = auth.queryParams()
        val params2 = auth.queryParams()
        assertNotEquals(
            "Two independently generated salts should differ",
            params1["s"],
            params2["s"],
        )
    }

    @Test
    fun `two calls produce different tokens`() {
        val auth = SubsonicAuth(username = "carol", password = "mypassword")
        val params1 = auth.queryParams()
        val params2 = auth.queryParams()
        assertNotEquals(
            "Different salts must produce different tokens",
            params1["t"],
            params2["t"],
        )
    }

    @Test
    fun `same password and salt always produce same token`() {
        val auth = SubsonicAuth(username = "bob", password = "hunter2")
        val p1 = auth.queryParams(salt = "abc123")
        val p2 = auth.queryParams(salt = "abc123")
        assertEquals(
            "Same password+salt must always produce the same token",
            p1["t"],
            p2["t"],
        )
    }

    // -------------------------------------------------------------------------
    // Password must never appear in toString()
    // -------------------------------------------------------------------------

    @Test
    fun `password absent from toString`() {
        val auth = SubsonicAuth(username = "grace", password = "s3cr3t!")
        assertFalse(
            "Password must not appear in toString()",
            auth.toString().contains("s3cr3t!"),
        )
    }
}
