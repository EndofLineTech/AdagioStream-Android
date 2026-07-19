package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AudiobookshelfApi endpoint tests (59p.1.1): request/payload shapes, the
 * items-in-progress recentEpisode discriminator, 404 semantics for episode
 * progress and session sync, ino string-or-number decode, /status discovery,
 * and URL builders (subpath + token placement).
 */
class AudiobookshelfApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AudiobookshelfApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val host = server.url("/").toString().trimEnd('/')
        api = AudiobookshelfApi(
            client = client,
            host = host,
            auth = AudiobookshelfAuth(
                client = client,
                host = host,
                username = "alice",
                password = "sesame",
                initialTokens = AudiobookshelfAuth.Tokens("acc-1", "ref-1"),
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mockJson(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    @Test
    fun `getStatus decodes auth methods and SSO form data`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"isInit":true,"authMethods":["local","openid"],
                    "authFormData":{"authOpenIDButtonText":"Corp SSO","authOpenIDAutoLaunch":true}}""",
            ),
        )

        val status = api.getStatus()

        assertTrue(status.supportsLocal)
        assertTrue(status.supportsOpenId)
        assertEquals("Corp SSO", status.openIdButtonText)
        // Unauthenticated endpoint — no Authorization header.
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `getStatus defaults to local auth when authMethods absent`() = runTest {
        server.enqueue(mockJson(200, """{"isInit":true}"""))

        val status = api.getStatus()

        assertTrue(status.supportsLocal)
        assertFalse(status.supportsOpenId)
    }

    // -------------------------------------------------------------------------
    // Libraries + items
    // -------------------------------------------------------------------------

    @Test
    fun `getLibraries returns all libraries with media types`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"libraries":[
                    {"id":"lib1","name":"Books","mediaType":"book"},
                    {"id":"lib2","name":"Casts","mediaType":"podcast"}]}""",
            ),
        )

        val libraries = api.getLibraries()

        assertEquals(2, libraries.size)
        assertTrue(libraries[0].isBook)
        assertTrue(libraries[1].isPodcast)
    }

    @Test
    fun `getLibraryItems sends pagination and sort params with bearer auth`() = runTest {
        server.enqueue(mockJson(200, """{"results":[{"id":"item1"}]}"""))

        val items = api.getLibraryItems("lib1")

        assertEquals(listOf("item1"), items.map { it.id })
        val request = server.takeRequest()
        assertEquals("/api/libraries/lib1/items", request.url.encodedPath)
        assertEquals("0", request.url.queryParameter("limit"))
        assertEquals("0", request.url.queryParameter("page"))
        assertEquals("1", request.url.queryParameter("minified"))
        assertEquals("media.metadata.title", request.url.queryParameter("sort"))
        assertEquals("Bearer acc-1", request.headers["Authorization"])
    }

    @Test
    fun `getItem decodes expanded detail with chapters and audio files`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"id":"item1","libraryId":"lib1","media":{
                    "duration":3600.5,
                    "metadata":{"title":"A Book","authorName":"An Author"},
                    "chapters":[{"id":0,"title":"Ch 1","start":0,"end":120.5}],
                    "audioFiles":[{"index":1,"ino":"649644248522215260","duration":1800.0}]},
                    "userMediaProgress":{"currentTime":42.0,"progress":0.011,"isFinished":false,"lastUpdate":1700000000000}}""",
            ),
        )

        val item = api.getItem("item1")

        assertEquals("A Book", item.media?.metadata?.title)
        assertEquals("An Author", item.media?.metadata?.displayAuthor)
        assertEquals(1, item.media?.chapters?.size)
        assertEquals(120.5, item.media?.chapters?.first()?.end!!, 0.0)
        assertEquals("649644248522215260", item.media?.audioFiles?.first()?.ino)
        assertEquals(42.0, item.userMediaProgress?.currentTime!!, 0.0)
        val request = server.takeRequest()
        assertEquals("1", request.url.queryParameter("expanded"))
        assertEquals("progress", request.url.queryParameter("include"))
    }

    @Test
    fun `audio file ino decodes from a JSON number as well as a string`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"id":"item1","media":{"audioFiles":[
                    {"index":1,"ino":649644248522215260},
                    {"index":2,"ino":"already-a-string"}]}}""",
            ),
        )

        val item = api.getItem("item1")

        assertEquals("649644248522215260", item.media?.audioFiles?.get(0)?.ino)
        assertEquals("already-a-string", item.media?.audioFiles?.get(1)?.ino)
    }

    // -------------------------------------------------------------------------
    // Continue Listening: recentEpisode discriminator
    // -------------------------------------------------------------------------

    @Test
    fun `items-in-progress discriminates podcast episodes by recentEpisode`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"libraryItems":[
                    {"id":"book1","media":{"metadata":{"title":"A Book"}},
                     "userMediaProgress":{"progress":0.4}},
                    {"id":"show1","media":{"metadata":{"title":"A Show"}},
                     "recentEpisode":{"id":"ep9","title":"Episode 9","duration":1800.0,
                       "pubDate":"Mon, 02 Jan 2006 15:04:05 GMT"}}]}""",
            ),
        )

        val items = api.getItemsInProgress()

        assertEquals(2, items.size)
        val (books, episodes) = items.partition { it.recentEpisode == null }
        assertEquals(listOf("book1"), books.map { it.id })
        assertEquals(listOf("show1"), episodes.map { it.id })
        assertEquals("ep9", episodes.single().recentEpisode?.id)
    }

    // -------------------------------------------------------------------------
    // Episode progress: 404 = never started
    // -------------------------------------------------------------------------

    @Test
    fun `episode progress returns null on 404`() = runTest {
        server.enqueue(mockJson(404, """{"error":"Not found"}"""))

        assertNull(api.getEpisodeProgress("show1", "ep9"))
    }

    @Test
    fun `episode progress decodes an existing record`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"libraryItemId":"show1","episodeId":"ep9","currentTime":600.0,
                    "progress":0.33,"isFinished":false,"lastUpdate":1700000000000}""",
            ),
        )

        val progress = api.getEpisodeProgress("show1", "ep9")

        assertNotNull(progress)
        assertEquals(600.0, progress?.currentTime!!, 0.0)
        assertEquals(
            "/api/me/progress/show1/ep9",
            server.takeRequest().url.encodedPath,
        )
    }

    // -------------------------------------------------------------------------
    // Playback sessions
    // -------------------------------------------------------------------------

    @Test
    fun `openPlaybackSession posts device info and mime types, decodes session`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"id":"sess1","playMethod":0,"currentTime":42.0,"duration":3600.0,
                    "chapters":[{"id":0,"title":"Ch 1","start":0,"end":120.0}],
                    "audioTracks":[{"index":1,"startOffset":0,"duration":1800.0,
                      "title":"Part 1","contentUrl":"/hls/sess1/part1.mp3"}]}""",
            ),
        )

        val session = api.openPlaybackSession(itemId = "item1", deviceId = "dev-1")

        assertEquals("sess1", session.id)
        assertEquals(0, session.playMethod)
        assertEquals("/hls/sess1/part1.mp3", session.audioTracks?.first()?.contentUrl)

        val request = server.takeRequest()
        assertEquals("/api/items/item1/play", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("\"dev-1\"", body["deviceInfo"]!!.jsonObject["deviceId"].toString())
        assertEquals("\"AdagioStream\"", body["deviceInfo"]!!.jsonObject["clientName"].toString())
        assertEquals("\"VLC\"", body["mediaPlayer"].toString())
        assertEquals(
            AudiobookshelfApi.SUPPORTED_MIME_TYPES.size,
            body["supportedMimeTypes"]!!.jsonArray.size,
        )
        assertEquals("false", body["forceDirectPlay"].toString())
        assertEquals("false", body["forceTranscode"].toString())
    }

    @Test
    fun `openPlaybackSession targets the episode path for podcast episodes`() = runTest {
        server.enqueue(mockJson(200, """{"id":"sess1"}"""))

        api.openPlaybackSession(itemId = "show1", episodeId = "ep9", deviceId = "dev-1")

        assertEquals("/api/items/show1/play/ep9", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `syncSession posts progress body`() = runTest {
        server.enqueue(mockJson(200, "{}"))

        api.syncSession("sess1", currentTime = 100.0, timeListened = 30.0, duration = 3600.0)

        val request = server.takeRequest()
        assertEquals("/api/session/sess1/sync", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("100.0", body["currentTime"].toString())
        assertEquals("30.0", body["timeListened"].toString())
        assertEquals("3600.0", body["duration"].toString())
    }

    @Test
    fun `syncSession surfaces a 404 as SessionNotFound`() = runTest {
        server.enqueue(mockJson(404, """{"error":"Not found"}"""))

        try {
            api.syncSession("sess-dead", currentTime = 1.0, timeListened = 1.0, duration = 2.0)
            fail("Expected SessionNotFound")
        } catch (_: AudiobookshelfApiException.SessionNotFound) {
            // expected — caller reopens via /play
        }
    }

    @Test
    fun `closeSession swallows failures`() = runTest {
        server.enqueue(mockJson(500, """{"error":"boom"}"""))

        api.closeSession("sess1") // must not throw

        assertEquals("/api/session/sess1/close", server.takeRequest().url.encodedPath)
    }

    // -------------------------------------------------------------------------
    // Batch progress payload shape
    // -------------------------------------------------------------------------

    @Test
    fun `batch progress body is a bare array omitting episodeId for books`() = runTest {
        server.enqueue(mockJson(200, "{}"))

        api.batchUpdateProgress(
            listOf(
                AbsProgressUpdate.of(
                    libraryItemId = "book1",
                    currentTime = 100.0,
                    duration = 200.0,
                    lastUpdate = 1700000000000L,
                ),
                AbsProgressUpdate.of(
                    libraryItemId = "show1",
                    episodeId = "ep9",
                    currentTime = 50.0,
                    duration = 100.0,
                    isFinished = true,
                    lastUpdate = 1700000000001L,
                ),
            ),
        )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/me/progress/batch/update", request.url.encodedPath)
        val array = Json.parseToJsonElement(request.body!!.utf8()).jsonArray
        assertEquals(2, array.size)

        val book = array[0].jsonObject
        assertFalse("Books must omit the episodeId key entirely", "episodeId" in book)
        assertEquals("\"book1\"", book["libraryItemId"].toString())
        assertEquals("0.5", book["progress"].toString())
        assertEquals("1700000000000", book["lastUpdate"].toString())

        val episode = array[1].jsonObject
        assertEquals("\"ep9\"", episode["episodeId"].toString())
        assertEquals("true", episode["isFinished"].toString())
    }

    @Test
    fun `batch progress with no updates sends nothing`() = runTest {
        api.batchUpdateProgress(emptyList())

        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // URL builders
    // -------------------------------------------------------------------------

    @Test
    fun `cover url carries size, format, and token in query`() {
        val url = api.coverUrl("item1")!!

        assertEquals("/api/items/item1/cover", url.encodedPath)
        assertEquals("400", url.queryParameter("width"))
        assertEquals("webp", url.queryParameter("format"))
        assertEquals("acc-1", url.queryParameter("token"))
    }

    @Test
    fun `resolveContentUrl resolves server-relative paths with token`() {
        val url = api.resolveContentUrl("/hls/sess1/part1.mp3")!!

        assertEquals("/hls/sess1/part1.mp3", url.encodedPath)
        assertEquals("acc-1", url.queryParameter("token"))
    }

    @Test
    fun `url builders preserve a reverse-proxy subpath in the host`() {
        val client = OkHttpClient()
        val host = server.url("/audiobookshelf").toString().trimEnd('/')
        val subpathApi = AudiobookshelfApi(
            client = client,
            host = host,
            auth = AudiobookshelfAuth(
                client, host, "alice", "sesame",
                initialTokens = AudiobookshelfAuth.Tokens("acc-1", "ref-1"),
            ),
        )

        assertEquals(
            "/audiobookshelf/api/items/item1/cover",
            subpathApi.coverUrl("item1")!!.encodedPath,
        )
        assertEquals(
            "/audiobookshelf/hls/s/p.mp3",
            subpathApi.resolveContentUrl("/hls/s/p.mp3")!!.encodedPath,
        )
        assertEquals(
            "/audiobookshelf/api/items/item1/file/42/download",
            subpathApi.fileDownloadUrl("item1", "42")!!.encodedPath,
        )
    }

    @Test
    fun `file download url carries no token in query`() {
        val url = api.fileDownloadUrl("item1", "649644248522215260")!!

        assertEquals("/api/items/item1/file/649644248522215260/download", url.encodedPath)
        assertNull(url.queryParameter("token"))
    }
}
