package com.adagiostream.android.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BodySizeCapInterceptorTest {

    private fun buildResponse(body: ResponseBody): Response =
        Response.Builder()
            .request(Request.Builder().url("http://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

    private fun chainReturning(response: Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns response.request
        every { chain.proceed(any()) } returns response
        return chain
    }

    @Test
    fun `passes through responses under the cap`() {
        val payload = "hello world".toResponseBody("text/plain".toMediaTypeOrNull())
        val response = buildResponse(payload)
        val interceptor = BodySizeCapInterceptor(maxBytes = 1024)

        val result = interceptor.intercept(chainReturning(response))

        assertEquals(200, result.code)
        assertEquals("hello world", result.body.string())
    }

    @Test
    fun `throws when Content-Length exceeds cap`() {
        // Body with a known content-length larger than the cap
        val bigPayload = "x".repeat(2048).toResponseBody("text/plain".toMediaTypeOrNull())
        val response = buildResponse(bigPayload)
        val interceptor = BodySizeCapInterceptor(maxBytes = 1024)

        val ex = assertThrows(IOException::class.java) {
            interceptor.intercept(chainReturning(response))
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("exceeds cap"))
    }

    @Test
    fun `throws when chunked body exceeds cap while streaming`() {
        // Build a ResponseBody from a Buffer with contentLength == -1 to simulate chunked transfer.
        val buffer = Buffer().writeUtf8("y".repeat(2048))
        val unknownLengthBody = buffer.asResponseBodyUnknownLength("text/plain".toMediaTypeOrNull())
        val response = buildResponse(unknownLengthBody)
        val interceptor = BodySizeCapInterceptor(maxBytes = 1024)

        val capped = interceptor.intercept(chainReturning(response))

        val ex = assertThrows(IOException::class.java) {
            // Force the source to be read; should throw once we cross the cap.
            capped.body.string()
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("exceeded cap"))
    }

    @Test
    fun `default cap is 512 MB`() {
        assertEquals(512L * 1024 * 1024, BodySizeCapInterceptor.DEFAULT_MAX_BYTES)
    }

    private fun Buffer.asResponseBodyUnknownLength(
        contentType: okhttp3.MediaType?,
    ): ResponseBody {
        val src = this
        return object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = contentType
            override fun contentLength(): Long = -1L
            override fun source(): okio.BufferedSource = src
        }
    }
}
