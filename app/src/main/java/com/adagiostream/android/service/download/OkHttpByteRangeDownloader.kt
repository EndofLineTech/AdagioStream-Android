package com.adagiostream.android.service.download

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Production [ByteRangeDownloader] backed by the app's shared [OkHttpClient]
 * (baw.6.1). Sends `Range: bytes=offset-` to resume partial downloads and reads
 * the total size from `Content-Range` (206) or `Content-Length` (200).
 *
 * Network execution is device-only; the resume/state logic that consumes this is
 * fully unit-tested via a fake (see TrackDownloaderTest).
 */
class OkHttpByteRangeDownloader(private val client: OkHttpClient) : ByteRangeDownloader {

    override suspend fun open(url: String, offset: Long): RangeResponse {
        val builder = Request.Builder().url(url).get()
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("HTTP $code for download")
        }

        val partial = response.code == 206
        val body = response.body ?: run {
            response.close()
            throw IOException("empty download body")
        }
        val contentLength = body.contentLength().takeIf { it >= 0 }
        val total: Long? = when {
            partial -> parseContentRangeTotal(response.header("Content-Range"))
                ?: contentLength?.let { offset + it }
            contentLength != null -> contentLength
            else -> null
        }
        val stream = body.byteStream()

        return object : RangeResponse {
            override val totalBytes: Long? = total
            override val partial: Boolean = partial
            override fun read(buffer: ByteArray): Int = stream.read(buffer)
            override fun close() = response.close()
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        // Format: "bytes 200-1023/1024" — the part after '/'.
        val slash = header?.substringAfterLast('/', missingDelimiterValue = "") ?: return null
        return slash.toLongOrNull()
    }
}
