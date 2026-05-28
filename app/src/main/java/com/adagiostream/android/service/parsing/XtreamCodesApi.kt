@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.adagiostream.android.service.parsing

import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request

class XtreamCodesApi(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getChannels(config: AccountType.XtreamCodes): List<Channel> =
        withContext(Dispatchers.IO) {
            val baseUrl = config.host.trimEnd('/')
            UrlSanitizer.requireHttpUrl(baseUrl)

            // Authenticate first to get allowed output formats
            val authResponse = authenticate(baseUrl, config.username, config.password)
                ?: throw Exception("Authentication failed: could not connect to server")
            if (authResponse.error != null) {
                throw Exception("Authentication failed: ${authResponse.error}")
            }
            if (authResponse.userInfo?.status != null && authResponse.userInfo.status != "Active") {
                throw Exception("Authentication failed: account status is ${authResponse.userInfo.status}")
            }
            val allowedFormats = authResponse.userInfo?.allowedOutputFormats ?: emptyList()
            val preferredExt = when {
                allowedFormats.contains("m3u8") -> "m3u8"
                allowedFormats.contains("ts") -> "ts"
                allowedFormats.isNotEmpty() -> allowedFormats.first()
                else -> "ts"
            }

            val categories = fetchCategories(baseUrl, config.username, config.password)
            val streams = fetchLiveStreams(baseUrl, config.username, config.password)

            val categoryMap = categories.associate { it.categoryId to it.categoryName }

            streams.map { stream ->
                val group = categoryMap[stream.categoryId] ?: "Uncategorized"
                val ext = if (stream.containerExtension.isNullOrBlank()) preferredExt else stream.containerExtension
                val streamUrl = "$baseUrl/live/${config.username}/${config.password}/${stream.streamId}.$ext"

                Channel(
                    id = stream.streamId.toString(),
                    name = stream.name,
                    streamURL = streamUrl,
                    logoURL = stream.streamIcon?.ifBlank { null }?.takeIf { UrlSanitizer.isHttpUrl(it) },
                    group = group,
                    epgChannelID = stream.epgChannelId?.ifBlank { null },
                    xtreamStreamId = stream.streamId,
                )
            }
        }

    suspend fun getShortEPG(
        streamId: Long,
        config: AccountType.XtreamCodes,
    ): List<EPGEntry> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = config.host.trimEnd('/')
            UrlSanitizer.requireHttpUrl(baseUrl)
            val url = "$baseUrl/player_api.php?username=${config.username}&password=${config.password}&action=get_short_epg&stream_id=$streamId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val epgResponse = response.body.byteStream().use { stream ->
                json.decodeFromStream<XtreamEPGResponse>(stream)
            }

            epgResponse.epgListings.mapNotNull { listing ->
                val title = decodeIfBase64(listing.title)
                val description = listing.description?.let { decodeIfBase64(it) }
                val start = listing.startTimestamp ?: return@mapNotNull null
                val end = listing.stopTimestamp ?: return@mapNotNull null
                val channelId = listing.epgId ?: return@mapNotNull null

                EPGEntry(
                    channelID = channelId,
                    title = title,
                    description = description?.ifBlank { null },
                    start = start * 1000L, // API returns seconds, EPGEntry uses millis
                    end = end * 1000L,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): AuthResponse? {
        return try {
            val url = "$baseUrl/player_api.php?username=$username&password=$password"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            response.body.byteStream().use { stream ->
                json.decodeFromStream<AuthResponse>(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCategories(
        baseUrl: String,
        username: String,
        password: String,
    ): List<XtreamCategory> {
        val url = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_categories"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        return response.body.byteStream().use { stream ->
            json.decodeFromStream<List<XtreamCategory>>(stream)
        }
    }

    private fun fetchLiveStreams(
        baseUrl: String,
        username: String,
        password: String,
    ): List<XtreamStream> {
        val url = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_streams"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        return response.body.byteStream().use { stream ->
            json.decodeFromStream<List<XtreamStream>>(stream)
        }
    }

    @VisibleForTesting
    internal fun decodeIfBase64(value: String): String {
        return try {
            val decoded = Base64.decode(value, Base64.DEFAULT)
            val result = String(decoded, Charsets.UTF_8)
            // Heuristic: if decoded string is printable, it was base64
            if (result.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?;:'-/()&@#" }) {
                result
            } else {
                value
            }
        } catch (_: Exception) {
            value
        }
    }

    @Serializable
    private data class AuthResponse(
        @SerialName("user_info") val userInfo: UserInfo? = null,
        @SerialName("server_info") val serverInfo: ServerInfo? = null,
        @SerialName("error") val error: String? = null,
    )

    @Serializable
    private data class UserInfo(
        @SerialName("username") val username: String? = null,
        @SerialName("status") val status: String? = null,
        @SerialName("allowed_output_formats") val allowedOutputFormats: List<String> = emptyList(),
    )

    @Serializable
    private data class ServerInfo(
        @SerialName("url") val url: String? = null,
        @SerialName("port") val port: String? = null,
        @SerialName("server_protocol") val serverProtocol: String? = null,
    )

    @Serializable
    private data class XtreamCategory(
        @SerialName("category_id") val categoryId: String,
        @SerialName("category_name") val categoryName: String,
    )

    @Serializable
    private data class XtreamStream(
        @SerialName("stream_id") val streamId: Long,
        @SerialName("name") val name: String,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("epg_channel_id") val epgChannelId: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("container_extension") val containerExtension: String? = null,
    )

    @Serializable
    private data class XtreamEPGResponse(
        @SerialName("epg_listings") val epgListings: List<XtreamEPGListing> = emptyList(),
    )

    @Serializable
    private data class XtreamEPGListing(
        @SerialName("title") val title: String,
        @SerialName("description") val description: String? = null,
        @SerialName("epg_id") val epgId: String? = null,
        @SerialName("start_timestamp") val startTimestamp: Long? = null,
        @SerialName("stop_timestamp") val stopTimestamp: Long? = null,
    )
}
