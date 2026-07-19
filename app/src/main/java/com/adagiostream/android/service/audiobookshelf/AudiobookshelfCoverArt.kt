package com.adagiostream.android.service.audiobookshelf

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import java.io.IOException

/**
 * Coil data model for an Audiobookshelf cover image (beads_adagio-59p.1.4).
 *
 * Mirrors [com.adagiostream.android.service.navidrome.NavidromeCoverArtRequest]:
 * the cover URL embeds a `?token=` query param (JWT — image loaders can't set
 * headers), and that access token ROTATES on refresh. Keying the Coil cache on
 * the URL string would therefore miss after every rotation. Instead
 * [AudiobookshelfCoverArtKeyer] derives a stable key from HOST + ITEM_ID +
 * WIDTH, and [AudiobookshelfCoverArtFetcher] builds the real tokened URL at
 * fetch time via [AudiobookshelfApi.coverUrl].
 *
 * The URL must NEVER be logged — it carries the access token.
 */
data class AudiobookshelfCoverArtRequest(
    val api: AudiobookshelfApi,
    val itemId: String,
    val width: Int = 400,
)

/**
 * Stable cache key: `abscover:<host>:<itemId>:<width>` — token intentionally
 * excluded (varies per session/rotation, never affects image content).
 */
class AudiobookshelfCoverArtKeyer : Keyer<AudiobookshelfCoverArtRequest> {
    override fun key(data: AudiobookshelfCoverArtRequest, options: Options): String =
        "abscover:${data.api.hostBase}:${data.itemId}:${data.width}"
}

/**
 * Fetches the cover with the app's shared [OkHttpClient], resolving the
 * tokened URL only at fetch time so the current access token is always used.
 */
class AudiobookshelfCoverArtFetcher(
    private val data: AudiobookshelfCoverArtRequest,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val url = data.api.coverUrl(data.itemId, width = data.width)
            ?: throw IOException("No access token for cover of item '${data.itemId}'")

        val request = Request.Builder().url(url).get().build()
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

        if (!response.isSuccessful) {
            response.close()
            throw IOException("Cover art HTTP ${response.code}")
        }

        return SourceFetchResult(
            source = ImageSource(
                source = response.body.source(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = response.body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    /** Factory registered in the Coil ComponentRegistry (see AppModule). */
    class Factory(private val client: OkHttpClient) : Fetcher.Factory<AudiobookshelfCoverArtRequest> {
        override fun create(
            data: AudiobookshelfCoverArtRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = AudiobookshelfCoverArtFetcher(data, client)
    }
}
