package com.adagiostream.android.service.parsing

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class XtreamCodesApi(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getChannels(config: ProviderType.XtreamCodes): List<Channel> =
        withContext(Dispatchers.IO) {
            val baseUrl = config.host.trimEnd('/')
            val categories = fetchCategories(baseUrl, config.username, config.password)
            val streams = fetchLiveStreams(baseUrl, config.username, config.password)

            val categoryMap = categories.associate { it.categoryId to it.categoryName }

            streams.map { stream ->
                val group = categoryMap[stream.categoryId] ?: "Uncategorized"
                val ext = if (stream.containerExtension.isNullOrBlank()) "ts" else stream.containerExtension
                val streamUrl = "$baseUrl/live/${config.username}/${config.password}/${stream.streamId}.$ext"

                Channel(
                    id = UUID.randomUUID().toString(),
                    name = stream.name,
                    streamURL = streamUrl,
                    logoURL = stream.streamIcon?.ifBlank { null },
                    group = group,
                    epgChannelID = stream.epgChannelId?.ifBlank { null },
                )
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
        val body = response.body?.string() ?: return emptyList()
        return json.decodeFromString<List<XtreamCategory>>(body)
    }

    private fun fetchLiveStreams(
        baseUrl: String,
        username: String,
        password: String,
    ): List<XtreamStream> {
        val url = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_streams"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        return json.decodeFromString<List<XtreamStream>>(body)
    }

    @Serializable
    private data class XtreamCategory(
        @kotlinx.serialization.SerialName("category_id") val categoryId: String,
        @kotlinx.serialization.SerialName("category_name") val categoryName: String,
    )

    @Serializable
    private data class XtreamStream(
        @kotlinx.serialization.SerialName("stream_id") val streamId: Long,
        @kotlinx.serialization.SerialName("name") val name: String,
        @kotlinx.serialization.SerialName("stream_icon") val streamIcon: String? = null,
        @kotlinx.serialization.SerialName("epg_channel_id") val epgChannelId: String? = null,
        @kotlinx.serialization.SerialName("category_id") val categoryId: String? = null,
        @kotlinx.serialization.SerialName("container_extension") val containerExtension: String? = null,
    )
}
