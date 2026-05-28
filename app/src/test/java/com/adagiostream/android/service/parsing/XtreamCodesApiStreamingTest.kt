package com.adagiostream.android.service.parsing

import com.adagiostream.android.model.AccountType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Streaming-path tests for [XtreamCodesApi]. Uses an OkHttp [Interceptor] to short-circuit
 * outbound requests with canned JSON, then exercises the public surface that fans out into
 * the four private fetch methods (each of which now decodes from a stream rather than
 * materializing the body as a `String`).
 */
@RunWith(RobolectricTestRunner::class)
class XtreamCodesApiStreamingTest {

    private fun buildClient(handler: (request: okhttp3.Request) -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val body = handler(req).toResponseBody("application/json".toMediaTypeOrNull())
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            })
            .build()

    @Test
    fun `getChannels decodes streaming responses end-to-end`() = runBlocking {
        val client = buildClient { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") ->
                    """[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"Sports"}]"""
                url.contains("action=get_live_streams") ->
                    """[
                        {"stream_id":101,"name":"CNN","stream_icon":"http://logo/cnn.png","category_id":"1","container_extension":"m3u8"},
                        {"stream_id":102,"name":"ESPN","stream_icon":null,"category_id":"2","container_extension":"ts"}
                    ]""".trimIndent()
                else ->
                    """{"user_info":{"username":"u","status":"Active","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"host","port":"80"}}"""
            }
        }
        val api = XtreamCodesApi(client)
        val channels = api.getChannels(
            AccountType.XtreamCodes(host = "http://example.com", username = "u", password = "p"),
        )

        assertEquals(2, channels.size)
        assertEquals("CNN", channels[0].name)
        assertEquals("News", channels[0].group)
        assertEquals("ESPN", channels[1].name)
        assertEquals("Sports", channels[1].group)
        // Stream URL composition uses container_extension when provided.
        assertTrue(channels[0].streamURL.endsWith("/live/u/p/101.m3u8"))
        assertTrue(channels[1].streamURL.endsWith("/live/u/p/102.ts"))
    }

    @Test
    fun `getChannels streams a moderate-size live_streams response without OOM`() = runBlocking {
        // Build a JSON array with enough entries to be a few MB; ensures the streaming
        // decoder handles realistic catalog sizes without OOM.
        val sb = StringBuilder()
        sb.append("[")
        val streamCount = 5_000
        for (i in 0 until streamCount) {
            if (i > 0) sb.append(",")
            sb.append(
                """{"stream_id":$i,"name":"Channel $i","stream_icon":"http://logo.example.com/${i % 200}.png","category_id":"${i % 50}","container_extension":"m3u8","epg_channel_id":"ch.$i"}""",
            )
        }
        sb.append("]")
        val liveStreamsJson = sb.toString()

        val client = buildClient { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") -> {
                    val cats = (0 until 50).joinToString(",") { """{"category_id":"$it","category_name":"Group $it"}""" }
                    "[$cats]"
                }
                url.contains("action=get_live_streams") -> liveStreamsJson
                else ->
                    """{"user_info":{"username":"u","status":"Active","allowed_output_formats":["m3u8"]}}"""
            }
        }
        val api = XtreamCodesApi(client)
        val channels = api.getChannels(
            AccountType.XtreamCodes(host = "http://example.com", username = "u", password = "p"),
        )

        assertEquals(streamCount, channels.size)
        assertEquals("Channel 0", channels[0].name)
        assertEquals("Group 0", channels[0].group)
        assertEquals("Channel ${streamCount - 1}", channels.last().name)
    }
}
