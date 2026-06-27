package com.adagiostream.android.service.download

import java.io.IOException

/**
 * Narrow seam over an HTTP byte-range GET (baw.6.1). Production is backed by
 * OkHttp + a `Range: bytes=offset-` header; tests supply an in-memory fake so the
 * resume/state-machine logic in [TrackDownloader] is verifiable without a network.
 */
interface ByteRangeDownloader {

    /**
     * Opens a streaming GET for [url] requesting bytes from [offset] onward.
     *
     * @throws IOException on connect/transport failure.
     */
    @Throws(IOException::class)
    suspend fun open(url: String, offset: Long): RangeResponse
}

/**
 * A draining handle over a range response. Callers [read] until -1, then [close].
 */
interface RangeResponse {

    /**
     * Total size of the complete resource in bytes if the server disclosed it
     * (derived from `Content-Range` total, or `offset + Content-Length`), else null.
     */
    val totalBytes: Long?

    /**
     * True when the server honored the range request (HTTP 206 Partial Content).
     * False (HTTP 200) means the body starts at byte 0 — the caller must restart.
     */
    val partial: Boolean

    /** Reads the next chunk into [buffer]; returns bytes read, or -1 at end of stream. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    fun close()
}
