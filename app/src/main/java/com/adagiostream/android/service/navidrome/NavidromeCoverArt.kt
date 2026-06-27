package com.adagiostream.android.service.navidrome

import coil3.ImageLoader
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
import coil3.decode.DataSource

/**
 * Coil data model for a Subsonic cover-art image (baw.2.2).
 *
 * Why this exists: Subsonic authentication includes a random salt (the `s=`
 * query parameter) that changes on every [NavidromeApi.getCoverArtUrl] call.
 * Loading images via a plain URL string therefore causes a cache miss on every
 * request — the key changes even though the image content is identical.
 *
 * The solution is a stable cache key derived from the HOST + COVER_ART_ID +
 * SIZE triple, which never changes for a given image.  [NavidromeCoverArtKeyer]
 * provides this key; [NavidromeCoverArtFetcher] builds the real authenticated
 * URL at fetch time (not at key time).
 *
 * Usage (Compose):
 * ```kotlin
 * AsyncImage(
 *     model = ImageRequest.Builder(context)
 *         .data(NavidromeCoverArtRequest(api = api, coverArtId = album.coverArt, size = 300))
 *         .build(),
 *     contentDescription = null,
 * )
 * ```
 *
 * Or use the [SubsonicCoverArtImage] composable which wraps this.
 *
 * @param api         The configured [NavidromeApi] — provides [NavidromeApi.hostBase]
 *                    for stable key derivation and [NavidromeApi.getCoverArtUrl] for
 *                    authenticated URL construction.
 * @param coverArtId  Subsonic cover-art identifier from [Artist.coverArt],
 *                    [Album.coverArt], or [Track.coverArt].
 * @param size        Optional thumbnail pixel size (square).  Affects the stable
 *                    key so that 64-px and 300-px thumbnails are cached separately.
 */
data class NavidromeCoverArtRequest(
    val api: NavidromeApi,
    val coverArtId: String,
    val size: Int? = null,
)

/**
 * Coil [Keyer] that produces a STABLE cache key for [NavidromeCoverArtRequest].
 *
 * Key format: `coverart:<host>:<coverArtId>:<size>`
 *
 * The host and coverArtId are static for a given server + item.  The auth salt
 * is intentionally excluded — it varies per-request but does not affect image
 * content.
 */
class NavidromeCoverArtKeyer : Keyer<NavidromeCoverArtRequest> {
    override fun key(data: NavidromeCoverArtRequest, options: Options): String =
        "coverart:${data.api.hostBase}:${data.coverArtId}:${data.size ?: "orig"}"
}

/**
 * Coil [Fetcher] that fetches cover art using the app's shared [OkHttpClient].
 *
 * At fetch time it calls [NavidromeApi.getCoverArtUrl] to generate a fresh
 * authenticated URL (with the current salt), executes the GET, and returns
 * the response body as a [SourceFetchResult].
 *
 * Registered via [Factory] in the Coil [coil3.ComponentRegistry] (see
 * AppModule / NavidromeImageModule).
 *
 * NOTE: No logging interceptor must be attached to [client] — the URL contains
 * Subsonic auth params (u / t / s) and must never be logged.
 */
class NavidromeCoverArtFetcher(
    private val data: NavidromeCoverArtRequest,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val url = data.api.getCoverArtUrl(data.coverArtId, data.size)
            ?: throw IOException("getCoverArtUrl returned null for id='${data.coverArtId}'")

        val request = Request.Builder().url(url).get().build()
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

        if (!response.isSuccessful) {
            response.close()
            throw IOException("Cover art HTTP ${response.code}")
        }

        val body = response.body ?: run {
            response.close()
            throw IOException("Cover art response had no body")
        }

        return SourceFetchResult(
            source = ImageSource(
                source = body.source(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    /** Factory registered in the Coil ComponentRegistry.  Takes the shared OkHttpClient. */
    class Factory(private val client: OkHttpClient) : Fetcher.Factory<NavidromeCoverArtRequest> {
        override fun create(
            data: NavidromeCoverArtRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = NavidromeCoverArtFetcher(data, client)
    }
}
