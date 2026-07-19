package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AudiobookshelfAuth tests (59p.1.1): login token extraction (both response
 * shapes), refresh rotation + persistence, concurrent-401 coalescing, and
 * refresh-failure → ReauthRequired with tokens cleared.
 */
class AudiobookshelfAuthTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** Every onTokensChanged callback value, in order (null = cleared). */
    private val tokenChanges = mutableListOf<AudiobookshelfAuth.Tokens?>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun auth(
        username: String? = "alice",
        password: String? = "sesame",
        initialTokens: AudiobookshelfAuth.Tokens? = null,
    ): AudiobookshelfAuth = AudiobookshelfAuth(
        client = client,
        host = server.url("/").toString().trimEnd('/'),
        username = username,
        password = password,
        initialTokens = initialTokens,
        onTokensChanged = { tokenChanges.add(it) },
    )

    private fun mockJson(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    @Test
    fun `login extracts access token under user and top-level refresh token`() = runTest {
        server.enqueue(
            mockJson(
                200,
                """{"user":{"id":"u1","accessToken":"acc-1","permissions":{"download":true},
                    "mediaProgress":[{"libraryItemId":"li1","currentTime":42.5,"progress":0.5,"isFinished":false,"lastUpdate":1700000000000}]},
                    "refreshToken":"ref-1"}""",
            ),
        )

        val session = auth().login()

        assertEquals("acc-1", session.tokens.accessToken)
        assertEquals("ref-1", session.tokens.refreshToken)
        assertTrue(session.canDownload)
        assertEquals(1, session.mediaProgress.size)
        assertEquals(42.5, session.mediaProgress[0].currentTime!!, 0.0)
        assertEquals(listOf(AudiobookshelfAuth.Tokens("acc-1", "ref-1")), tokenChanges)
    }

    @Test
    fun `login extracts refresh token nested under user when top-level absent`() = runTest {
        server.enqueue(
            mockJson(200, """{"user":{"accessToken":"acc-1","refreshToken":"ref-nested"}}"""),
        )

        val session = auth().login()

        assertEquals("ref-nested", session.tokens.refreshToken)
    }

    @Test
    fun `login sends json content type and x-return-tokens headers with credentials body`() = runTest {
        server.enqueue(mockJson(200, """{"user":{"accessToken":"a","refreshToken":"r"}}"""))

        auth().login()

        val request = server.takeRequest()
        assertEquals("/login", request.url.encodedPath)
        assertEquals("true", request.headers["x-return-tokens"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"username\":\"alice\""))
        assertTrue(body.contains("\"password\":\"sesame\""))
    }

    @Test
    fun `login failure surfaces LoginFailed with status code`() = runTest {
        server.enqueue(mockJson(401, """{"error":"Invalid user or password"}"""))

        try {
            auth().login()
            fail("Expected LoginFailed")
        } catch (e: AudiobookshelfApiException.LoginFailed) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun `login with no password throws ReauthRequired without a network call`() = runTest {
        try {
            auth(password = null).login()
            fail("Expected ReauthRequired")
        } catch (_: AudiobookshelfApiException.ReauthRequired) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // Refresh rotation
    // -------------------------------------------------------------------------

    @Test
    fun `refresh sends x-refresh-token header and persists the rotated pair`() = runTest {
        server.enqueue(
            mockJson(200, """{"user":{"accessToken":"acc-2"},"refreshToken":"ref-2"}"""),
        )
        val a = auth(initialTokens = AudiobookshelfAuth.Tokens("acc-1", "ref-1"))

        a.refresh()

        val request = server.takeRequest()
        assertEquals("/auth/refresh", request.url.encodedPath)
        assertEquals("ref-1", request.headers["x-refresh-token"])
        assertEquals("acc-2", a.currentAccessToken())
        assertEquals(listOf(AudiobookshelfAuth.Tokens("acc-2", "ref-2")), tokenChanges)
    }

    @Test
    fun `refresh decodes token pair nested under user`() = runTest {
        server.enqueue(
            mockJson(200, """{"user":{"accessToken":"acc-2","refreshToken":"ref-2"}}"""),
        )
        val a = auth(initialTokens = AudiobookshelfAuth.Tokens("acc-1", "ref-1"))

        a.refresh()

        assertEquals(
            listOf(AudiobookshelfAuth.Tokens("acc-2", "ref-2")),
            tokenChanges,
        )
    }

    @Test
    fun `refresh failure clears tokens and throws ReauthRequired`() = runTest {
        server.enqueue(mockJson(401, """{"error":"Invalid refresh token"}"""))
        val a = auth(initialTokens = AudiobookshelfAuth.Tokens("acc-1", "ref-1"))

        try {
            a.refresh()
            fail("Expected ReauthRequired")
        } catch (_: AudiobookshelfApiException.ReauthRequired) {
            // expected
        }
        assertNull(a.currentAccessToken())
        assertEquals(listOf<AudiobookshelfAuth.Tokens?>(null), tokenChanges)
    }

    @Test
    fun `refresh with malformed body clears tokens and throws ReauthRequired`() = runTest {
        server.enqueue(mockJson(200, """{"unexpected":"shape"}"""))
        val a = auth(initialTokens = AudiobookshelfAuth.Tokens("acc-1", "ref-1"))

        try {
            a.refresh()
            fail("Expected ReauthRequired")
        } catch (_: AudiobookshelfApiException.ReauthRequired) {
            // expected
        }
        assertNull(a.currentAccessToken())
    }

    // -------------------------------------------------------------------------
    // 401 → refresh → retry
    // -------------------------------------------------------------------------

    /**
     * Dispatcher that authorizes only `Bearer <validToken>`, 401s stale tokens,
     * and rotates the pair on `/auth/refresh` — path-keyed so concurrent
     * request ordering can't break determinism.
     */
    private inner class RotatingDispatcher : Dispatcher() {
        val refreshCalls = AtomicInteger(0)

        @Volatile var validAccess = "acc-1"

        @Volatile var validRefresh = "ref-1"

        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    val n = refreshCalls.incrementAndGet()
                    if (request.headers["x-refresh-token"] != validRefresh) {
                        return mockJson(401, """{"error":"Invalid refresh token"}""")
                    }
                    validAccess = "acc-${n + 1}"
                    validRefresh = "ref-${n + 1}"
                    mockJson(
                        200,
                        """{"user":{"accessToken":"$validAccess"},"refreshToken":"$validRefresh"}""",
                    )
                }
                else -> {
                    if (request.headers["Authorization"] == "Bearer $validAccess") {
                        mockJson(200, """{"ok":true}""")
                    } else {
                        mockJson(401, """{"error":"expired"}""")
                    }
                }
            }
        }
    }

    private fun probeRequest(): Request =
        Request.Builder().url(server.url("/api/probe")).get().build()

    @Test
    fun `execute refreshes once on 401 and retries with the rotated token`() = runTest {
        val dispatcher = RotatingDispatcher()
        server.dispatcher = dispatcher
        // Stale pair: access is expired but the refresh token is the valid one.
        dispatcher.validAccess = "acc-live"
        val a = auth(initialTokens = AudiobookshelfAuth.Tokens("acc-stale", "ref-1"))

        val response = a.execute(probeRequest())

        response.use { assertEquals(200, it.code) }
        assertEquals(1, dispatcher.refreshCalls.get())
        assertEquals(dispatcher.validAccess, a.currentAccessToken())
    }

    @Test
    fun `concurrent 401s coalesce into a single refresh`() = runTest {
        val dispatcher = RotatingDispatcher()
        server.dispatcher = dispatcher
        dispatcher.validAccess = "acc-live" // everyone holds a stale token
        val a = auth(initialTokens = AudiobookshelfAuth.Tokens("acc-stale", "ref-1"))

        val codes = coroutineScope {
            (1..5).map {
                async { a.execute(probeRequest()).use { resp -> resp.code } }
            }.awaitAll()
        }

        assertEquals(listOf(200, 200, 200, 200, 200), codes)
        // The first 401 triggers the one refresh; the other four see the
        // rotated access token and retry without refreshing (a second refresh
        // would POST the already-rotated, now-invalid refresh token).
        assertEquals(1, dispatcher.refreshCalls.get())
    }

    @Test
    fun `execute logs in first when no tokens are stored`() = runTest {
        server.enqueue(
            mockJson(200, """{"user":{"accessToken":"acc-1"},"refreshToken":"ref-1"}"""),
        )
        server.enqueue(mockJson(200, """{"ok":true}"""))
        val a = auth()

        val response = a.execute(probeRequest())

        response.use { assertEquals(200, it.code) }
        assertEquals("/login", server.takeRequest().url.encodedPath)
        val apiCall = server.takeRequest()
        assertEquals("/api/probe", apiCall.url.encodedPath)
        assertEquals("Bearer acc-1", apiCall.headers["Authorization"])
    }
}
