package com.adagiostream.android.service.parsing

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XtreamCodesApiTest {

    private val api = XtreamCodesApi(okhttp3.OkHttpClient())

    @Test
    fun `decodeIfBase64 decodes valid base64 encoded text`() {
        val original = "Hello World"
        val encoded = Base64.encodeToString(original.toByteArray(), Base64.DEFAULT)
        val result = api.decodeIfBase64(encoded.trim())
        assertEquals(original, result)
    }

    @Test
    fun `decodeIfBase64 returns original for plain text`() {
        val plainText = "Just a regular title"
        val result = api.decodeIfBase64(plainText)
        assertEquals(plainText, result)
    }

    @Test
    fun `decodeIfBase64 returns original for empty string`() {
        val result = api.decodeIfBase64("")
        assertEquals("", result)
    }

    @Test
    fun `decodeIfBase64 decodes base64 with punctuation`() {
        val original = "News at 10: Breaking!"
        val encoded = Base64.encodeToString(original.toByteArray(), Base64.DEFAULT)
        val result = api.decodeIfBase64(encoded.trim())
        assertEquals(original, result)
    }

    @Test
    fun `decodeIfBase64 handles base64 with special chars that fail heuristic`() {
        // Encode something with non-printable chars — result should fall back to original
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val encoded = Base64.encodeToString(bytes, Base64.DEFAULT)
        val result = api.decodeIfBase64(encoded.trim())
        // Should return the original encoded string because decoded bytes aren't "printable"
        assertEquals(encoded.trim(), result)
    }

    @Test
    fun `decodeIfBase64 decodes base64 with parentheses and ampersand`() {
        val original = "Show (Live) & More"
        val encoded = Base64.encodeToString(original.toByteArray(), Base64.DEFAULT)
        val result = api.decodeIfBase64(encoded.trim())
        assertEquals(original, result)
    }

    @Test
    fun `decodeIfBase64 decodes base64 with at sign and hash`() {
        val original = "user@host #channel"
        val encoded = Base64.encodeToString(original.toByteArray(), Base64.DEFAULT)
        val result = api.decodeIfBase64(encoded.trim())
        assertEquals(original, result)
    }

    @Test
    fun `decodeIfBase64 decodes single word`() {
        val original = "News"
        val encoded = Base64.encodeToString(original.toByteArray(), Base64.DEFAULT)
        val result = api.decodeIfBase64(encoded.trim())
        assertEquals(original, result)
    }
}
