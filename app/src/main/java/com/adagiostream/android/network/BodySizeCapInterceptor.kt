package com.adagiostream.android.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

/**
 * Rejects HTTP responses whose bodies exceed [maxBytes]. Defense-in-depth against
 * provider responses (e.g. raw Xtream `get_live_streams` JSON or large XMLTV EPGs)
 * that could otherwise drive the app to OutOfMemoryError when materialized in memory.
 *
 * Checks `Content-Length` first (fast-path: rejects before any body bytes are read).
 * Also wraps the body source with a counting source so chunked/streaming responses
 * without an advertised length are still capped while being read.
 */
class BodySizeCapInterceptor(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val advertised = response.body.contentLength()
        if (advertised in 1..Long.MAX_VALUE && advertised > maxBytes) {
            response.close()
            throw IOException(
                "Response body $advertised bytes exceeds cap ${maxBytes / (1024 * 1024)} MB",
            )
        }
        // Fall back to a counting source so chunked responses (advertised == -1) are also capped.
        val original = response.body
        val capped = CappedResponseBody(original, maxBytes)
        return response.newBuilder().body(capped).build()
    }

    private class CappedResponseBody(
        private val delegate: ResponseBody,
        private val maxBytes: Long,
    ) : ResponseBody() {
        private val bufferedSource: BufferedSource by lazy {
            CountingSource(delegate.source(), maxBytes).buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = bufferedSource
    }

    private class CountingSource(
        delegate: Source,
        private val maxBytes: Long,
    ) : ForwardingSource(delegate) {
        private var totalRead: Long = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n != -1L) {
                totalRead += n
                if (totalRead > maxBytes) {
                    throw IOException(
                        "Response body exceeded cap ${maxBytes / (1024 * 1024)} MB " +
                            "while streaming (read $totalRead bytes)",
                    )
                }
            }
            return n
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024 * 1024
    }
}
