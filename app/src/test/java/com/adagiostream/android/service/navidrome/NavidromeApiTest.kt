package com.adagiostream.android.service.navidrome

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NavidromeApi unit tests using MockWebServer.
 *
 * Required coverage per baw.1.4:
 *  1. Envelope ok → success (ping)
 *  2. status=failed code 40 → AuthFailed
 *  3. Non-Subsonic/HTML body → NotSubsonicServer
 *  4. HTTP 500 → ServerError(500)
 */
class NavidromeApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NavidromeApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // Short timeouts to keep tests fast; no logging interceptor (production constraint).
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        api = NavidromeApi(
            client = client,
            host = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "sesame",
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mockResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    // -------------------------------------------------------------------------
    // 1. Envelope ok → success
    // -------------------------------------------------------------------------

    @Test
    fun `ping returns normally on status ok envelope`() = runTest {
        server.enqueue(mockResponse(200, """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
        // Must not throw
        api.ping()
    }

    // -------------------------------------------------------------------------
    // download.view URL (baw.6.1)
    // -------------------------------------------------------------------------

    @Test
    fun `downloadUrl points at download view with the track id and auth params`() {
        val url = api.downloadUrl("track-42")!!
        assertEquals("/rest/download.view", url.encodedPath)
        assertEquals("track-42", url.queryParameter("id"))
        assertEquals("alice", url.queryParameter("u"))
        org.junit.Assert.assertNotNull("download url must carry an auth token", url.queryParameter("t"))
        org.junit.Assert.assertNotNull("download url must carry a salt", url.queryParameter("s"))
    }

    // -------------------------------------------------------------------------
    // 2. status=failed code 40 → AuthFailed
    // -------------------------------------------------------------------------

    @Test
    fun `ping throws AuthFailed on status failed with error code 40`() = runTest {
        server.enqueue(
            mockResponse(
                200,
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""",
            ),
        )
        try {
            api.ping()
            org.junit.Assert.fail("Expected AuthFailed but ping() returned normally")
        } catch (e: NavidromeApiException.AuthFailed) {
            // Expected
        }
    }

    @Test
    fun `ping throws AuthFailed on status failed with error code 41`() = runTest {
        server.enqueue(
            mockResponse(
                200,
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":41,"message":"Token authentication not supported"}}}""",
            ),
        )
        try {
            api.ping()
            org.junit.Assert.fail("Expected AuthFailed but ping() returned normally")
        } catch (e: NavidromeApiException.AuthFailed) {
            // Expected
        }
    }

    // -------------------------------------------------------------------------
    // 3. Non-Subsonic/HTML body → NotSubsonicServer
    // -------------------------------------------------------------------------

    @Test
    fun `ping throws NotSubsonicServer on HTML response body`() = runTest {
        server.enqueue(
            mockResponse(200, "<html><body>Not a Subsonic server</body></html>"),
        )
        try {
            api.ping()
            org.junit.Assert.fail("Expected NotSubsonicServer but ping() returned normally")
        } catch (e: NavidromeApiException.NotSubsonicServer) {
            // Expected
        }
    }

    @Test
    fun `ping throws NotSubsonicServer on valid JSON without subsonic-response key`() = runTest {
        server.enqueue(
            mockResponse(200, """{"message":"not subsonic","code":200}"""),
        )
        try {
            api.ping()
            org.junit.Assert.fail("Expected NotSubsonicServer but ping() returned normally")
        } catch (e: NavidromeApiException.NotSubsonicServer) {
            // Expected
        }
    }

    // -------------------------------------------------------------------------
    // 4. HTTP 500 → ServerError(500)
    // -------------------------------------------------------------------------

    @Test
    fun `ping throws ServerError(500) on HTTP 500`() = runTest {
        server.enqueue(mockResponse(500, "Internal Server Error"))
        try {
            api.ping()
            org.junit.Assert.fail("Expected ServerError but ping() returned normally")
        } catch (e: NavidromeApiException.ServerError) {
            assertEquals(500, e.statusCode)
        }
    }

    // -------------------------------------------------------------------------
    // Additional coverage: non-auth SubsonicError
    // -------------------------------------------------------------------------

    @Test
    fun `ping throws SubsonicError on status failed with non-auth code`() = runTest {
        server.enqueue(
            mockResponse(
                200,
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":50,"message":"Permission denied"}}}""",
            ),
        )
        try {
            api.ping()
            org.junit.Assert.fail("Expected SubsonicError but ping() returned normally")
        } catch (e: NavidromeApiException.SubsonicError) {
            assertEquals(50, e.code)
            assertEquals("Permission denied", e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Request structure: path + auth params present
    // -------------------------------------------------------------------------

    @Test
    fun `ping request targets rest slash ping dot view`() = runTest {
        server.enqueue(mockResponse(200, """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
        api.ping()
        val request = server.takeRequest()
        assertEquals("/rest/ping.view", request.url.encodedPath)
    }

    @Test
    fun `ping request contains all six auth query params`() = runTest {
        server.enqueue(mockResponse(200, """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
        api.ping()
        val request = server.takeRequest()
        val url = request.url

        assertEquals("alice", url.queryParameter("u"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals(SubsonicAuth.CLIENT_NAME, url.queryParameter("c"))
        assertEquals(SubsonicAuth.API_VERSION, url.queryParameter("v"))
        assert(url.queryParameter("t") != null) { "t (token) param must be present" }
        assert(url.queryParameter("s") != null) { "s (salt) param must be present" }
    }

    // -------------------------------------------------------------------------
    // Password never in toString
    // -------------------------------------------------------------------------

    @Test
    fun `password absent from toString`() {
        assert(!api.toString().contains("sesame")) {
            "Password must not appear in toString()"
        }
    }

    // -------------------------------------------------------------------------
    // Scrobble (baw.5.1)
    // -------------------------------------------------------------------------

    private val okEnvelope = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

    @Test
    fun `scrobble nowPlaying callsCorrectEndpoint`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.scrobble("song-1", submission = false)
        val request = server.takeRequest()
        assertEquals("/rest/scrobble.view", request.url.encodedPath)
        assertEquals("song-1", request.url.queryParameter("id"))
        assertEquals("false", request.url.queryParameter("submission"))
    }

    @Test
    fun `scrobble submission callsCorrectEndpoint`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.scrobble("song-1", submission = true)
        val request = server.takeRequest()
        assertEquals("/rest/scrobble.view", request.url.encodedPath)
        assertEquals("song-1", request.url.queryParameter("id"))
        assertEquals("true", request.url.queryParameter("submission"))
    }

    // -------------------------------------------------------------------------
    // Star / Unstar (baw.5.2)
    // -------------------------------------------------------------------------

    @Test
    fun `star callsCorrectEndpoint`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.star("song-2")
        val request = server.takeRequest()
        assertEquals("/rest/star.view", request.url.encodedPath)
        assertEquals("song-2", request.url.queryParameter("id"))
    }

    @Test
    fun `unstar callsCorrectEndpoint`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.unstar("song-3")
        val request = server.takeRequest()
        assertEquals("/rest/unstar.view", request.url.encodedPath)
        assertEquals("song-3", request.url.queryParameter("id"))
    }

    // -------------------------------------------------------------------------
    // setRating (baw.5.2)
    // -------------------------------------------------------------------------

    @Test
    fun `setRating clamps rating above 5 to 5`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.setRating("song-4", 10)
        val request = server.takeRequest()
        assertEquals("/rest/setRating.view", request.url.encodedPath)
        assertEquals("song-4", request.url.queryParameter("id"))
        assertEquals("5", request.url.queryParameter("rating"))
    }

    @Test
    fun `setRating clamps rating below 0 to 0`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.setRating("song-5", -1)
        val request = server.takeRequest()
        assertEquals("/rest/setRating.view", request.url.encodedPath)
        assertEquals("0", request.url.queryParameter("rating"))
    }

    @Test
    fun `setRating passes valid rating unchanged`() = runTest {
        server.enqueue(mockResponse(200, okEnvelope))
        api.setRating("song-6", 3)
        val request = server.takeRequest()
        assertEquals("/rest/setRating.view", request.url.encodedPath)
        assertEquals("3", request.url.queryParameter("rating"))
    }
}
