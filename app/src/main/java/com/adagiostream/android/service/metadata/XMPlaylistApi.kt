package com.adagiostream.android.service.metadata

import android.util.Log
import com.adagiostream.android.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class XMPlaylistApi(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getRecentTrack(slug: String): TrackMetadata? = withContext(Dispatchers.IO) {
        try {
            val url = "https://xmplaylist.com/api/station/$slug"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val tracks = json.decodeFromString<List<XMTrack>>(body)
            val latest = tracks.firstOrNull() ?: return@withContext null
            TrackMetadata(
                artist = latest.track?.artists?.joinToString(", ") { it.name } ?: return@withContext null,
                title = latest.track?.name ?: return@withContext null,
                album = latest.track?.album,
                albumArtURL = latest.track?.cover,
                timestamp = latest.startTime ?: 0L,
            )
        } catch (e: Exception) {
            Log.d("XMPlaylistApi", "Failed to fetch track for $slug: ${e.message}")
            null
        }
    }

    companion object {
        val CHANNEL_SLUG_MAP = mapOf(
            "siriusxm hits 1" to "siriusxmhits1",
            "hits 1" to "siriusxmhits1",
            "siriusxm the highway" to "thehighway",
            "the highway" to "thehighway",
            "siriusxm the heat" to "theheat",
            "the heat" to "theheat",
            "siriusxm fly" to "fly",
            "siriusxm chill" to "siriusxmchill",
            "bpm" to "bpm",
            "siriusxm love" to "siriusxmlove",
            "the pulse" to "thepulse",
            "pop rocks" to "poprocks",
            "pop2k" to "pop2k",
            "90s on 9" to "90son9",
            "80s on 8" to "80son8",
            "70s on 7" to "70son7",
            "classic rewind" to "classicrewind",
            "classic vinyl" to "classicvinyl",
            "the bridge" to "thebridge",
            "willie's roadhouse" to "williesroadhouse",
            "outlaw country" to "outlawcountry",
            "prime country" to "primecountry",
            "the blend" to "theblend",
            "coffee house" to "coffeehouse",
            "watercolors" to "watercolors",
            "real jazz" to "realjazz",
            "siriusxm u" to "siriusxmu",
            "shade 45" to "shade45",
            "hip-hop nation" to "hiphopnation",
            "heart & soul" to "heartandsoul",
            "the groove" to "thegroove",
            "diplo's revolution" to "diplosrevolution",
            "alt nation" to "altnation",
            "lithium" to "lithium",
            "octane" to "octane",
            "turbo" to "turbo",
            "hair nation" to "hairnation",
            "ozzy's boneyard" to "ozzysboneyard",
            "liquid metal" to "liquidmetal",
            "venus" to "venus",
            "utopia" to "utopia",
            "studio 54 radio" to "studio54",
            "yacht rock radio" to "yachtrockradio",
            "bb king's bluesville" to "bluesville",
            "bluesville" to "bluesville",
            "spa" to "spa",
            "escape" to "escape",
            "enlighten" to "enlighten",
        )

        fun slugForChannel(channelName: String): String? {
            return CHANNEL_SLUG_MAP[channelName.lowercase().trim()]
        }
    }

    @Serializable
    private data class XMTrack(
        @SerialName("track") val track: XMTrackInfo? = null,
        @SerialName("startTime") val startTime: Long? = null,
    )

    @Serializable
    private data class XMTrackInfo(
        @SerialName("name") val name: String? = null,
        @SerialName("artists") val artists: List<XMArtist> = emptyList(),
        @SerialName("album") val album: String? = null,
        @SerialName("cover") val cover: String? = null,
    )

    @Serializable
    private data class XMArtist(
        @SerialName("name") val name: String = "",
    )
}
