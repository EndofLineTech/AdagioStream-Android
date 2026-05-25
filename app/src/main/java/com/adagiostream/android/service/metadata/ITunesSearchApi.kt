package com.adagiostream.android.service.metadata

import com.adagiostream.android.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class ITunesSearchApi(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Look up a track on Apple Music via the iTunes Search API.
     * Returns a direct trackViewUrl if found, or null on failure/no results.
     */
    suspend fun lookupTrackUrl(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val term = URLEncoder.encode("$artist $title", "UTF-8")
            val url = "https://itunes.apple.com/search?term=$term&media=music&limit=1"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body.string()
            val result = json.decodeFromString<ITunesSearchResponse>(body)
            result.results.firstOrNull()?.trackViewUrl
        } catch (e: Exception) {
            DebugLogger.log("iTunes Search API failed: ${e.message}", DebugLogger.Category.SXM)
            null
        }
    }

    @Serializable
    private data class ITunesSearchResponse(
        val resultCount: Int = 0,
        val results: List<ITunesResult> = emptyList(),
    )

    @Serializable
    private data class ITunesResult(
        val trackViewUrl: String? = null,
    )
}
