package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * AudiobookshelfOidc tests (59p.1.2): PKCE generation, strict query-value
 * percent-encoding (+ / =), Location-header extraction without following the
 * redirect, JSON-body fallbacks, Set-Cookie capture + explicit Cookie-header
 * replay, CSRF state validation, both token-payload shapes, and error-detail
 * truncation.
 */
class AudiobookshelfOidcTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var oidc: AudiobookshelfOidc
    private lateinit var host: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // Redirect-following ON: proves the flow's no-redirect behavior comes
        // from its own derived client, not from this shared one.
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        host = server.url("/").toString().trimEnd('/')
        oidc = AudiobookshelfOidc(client)
    }

    @After
    fun tearDown() {
        server.close()
    }

    // -------------------------------------------------------------------------
    // PKCE + state (pure)
    // -------------------------------------------------------------------------

    @Test
    fun `makePkce produces 43-char base64url verifier with S256 challenge`() {
        val pkce = AudiobookshelfOidc.makePkce()

        assertEquals(43, pkce.verifier.length)
        assertTrue(pkce.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))

        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expectedChallenge, pkce.challenge)

        // Fresh randomness per call; verifier never leaks via toString.
        assertNotEquals(pkce.verifier, AudiobookshelfOidc.makePkce().verifier)
        assertEquals("Pkce(****)", pkce.toString())
    }

    @Test
    fun `makeState produces unique 43-char base64url values`() {
        val state = AudiobookshelfOidc.makeState()
        assertTrue(state.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(state, AudiobookshelfOidc.makeState())
    }

    // -------------------------------------------------------------------------
    // Query-value encoding (pure)
    // -------------------------------------------------------------------------

    @Test
    fun `encodeQueryValue percent-encodes plus slash equals and space`() {
        assertEquals("%2B%2F%3D", AudiobookshelfOidc.encodeQueryValue("+/="))
        assertEquals("a%20b", AudiobookshelfOidc.encodeQueryValue("a b"))
        // base64url charset passes through untouched.
        assertEquals("aA1-_", AudiobookshelfOidc.encodeQueryValue("aA1-_"))
    }

    @Test
    fun `buildAuthorizeUrl percent-encodes reserved characters in query values`() {
        val url = oidc.buildAuthorizeUrl(
            host = "https://abs.example.com/audiobookshelf",
            challenge = "ch+al/le=nge",
            state = "st+ate=",
        )!!.toString()

        assertTrue(url, url.contains("code_challenge=ch%2Bal%2Fle%3Dnge"))
        assertTrue(url, url.contains("state=st%2Bate%3D"))
        assertTrue(url, url.contains("code_challenge_method=S256"))
        assertTrue(url, url.contains("redirect_uri=adagiostream%3A%2F%2Foauth"))
        assertTrue(url, url.contains("client_id=AdagioStream"))
        assertTrue(url, url.contains("response_type=code"))
        // Reverse-proxy subpath preserved; no literal '+' anywhere in the query.
        assertTrue(url, url.startsWith("https://abs.example.com/audiobookshelf/auth/openid?"))
        assertFalse(url, url.substringAfter('?').contains('+'))
    }

    // -------------------------------------------------------------------------
    // Step 1: authorize
    // -------------------------------------------------------------------------

    @Test
    fun `startAuthorization reads Location from 302 without following and captures all cookies`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                // External IdP — a follow attempt would fail this test loudly.
                .addHeader("Location", "https://idp.example.com/authorize?client_id=abs")
                .addHeader("Set-Cookie", "auth_method=openid; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "connect.sid=s%3Aabc123; Path=/; HttpOnly")
                .build(),
        )

        val result = oidc.startAuthorization(host, challenge = "chal+1", state = "state=1")

        assertEquals("https://idp.example.com/authorize?client_id=abs", result.authorizeUrl)
        assertEquals("auth_method=openid; connect.sid=s%3Aabc123", result.cookieHeader)
        // Exactly one request — the 302 was NOT followed.
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("/auth/openid", recorded.url.encodedPath)
        assertTrue(recorded.target.contains("code_challenge=chal%2B1"))
        assertTrue(recorded.target.contains("state=state%3D1"))
        // Secrets stay out of toString.
        assertEquals("Authorization(****)", result.toString())
        // And the shared client's redirect policy was not mutated.
        assertTrue(client.followRedirects)
    }

    @Test
    fun `startAuthorization falls back to JSON body keys on 200`() = runTest {
        for (key in listOf("authorizationUrl", "authorization_url", "url")) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"$key":"https://idp.example.com/via-$key"}""")
                    .build(),
            )

            val result = oidc.startAuthorization(host, "chal", "state")

            assertEquals("https://idp.example.com/via-$key", result.authorizeUrl)
        }
    }

    @Test
    fun `startAuthorization throws AuthUrlMissing when 200 body has no url`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        try {
            oidc.startAuthorization(host, "chal", "state")
            fail("Expected OidcAuthUrlMissing")
        } catch (e: AudiobookshelfApiException.OidcAuthUrlMissing) {
            // expected
        }
    }

    @Test
    fun `startAuthorization surfaces trimmed single-line capped error detail`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(500).body("  first line\nsecond line  ").build(),
        )

        try {
            oidc.startAuthorization(host, "chal", "state")
            fail("Expected OidcFailed")
        } catch (e: AudiobookshelfApiException.OidcFailed) {
            assertEquals("sign-in start", e.step)
            assertEquals(500, e.statusCode)
            assertTrue(e.message!!.contains("first line second line"))
            assertFalse(e.message!!.contains("\n"))
        }
    }

    @Test
    fun `safeErrorDetail caps at 200 chars with ellipsis`() {
        val detail = oidc.safeErrorDetail("x".repeat(250))!!
        assertEquals(201, detail.length)
        assertTrue(detail.endsWith("…"))
        assertEquals("x".repeat(200), detail.dropLast(1))

        assertNull(oidc.safeErrorDetail("   \n  "))
        assertEquals("a b", oidc.safeErrorDetail("a\nb"))
    }

    // -------------------------------------------------------------------------
    // Cookie assembly (pure)
    // -------------------------------------------------------------------------

    @Test
    fun `cookieHeaderFrom strips attributes and drops malformed entries`() {
        assertEquals(
            "a=1; b=2",
            oidc.cookieHeaderFrom(
                listOf("a=1; Path=/; HttpOnly; SameSite=Lax", "b=2", "garbage-without-equals"),
            ),
        )
        assertEquals("", oidc.cookieHeaderFrom(emptyList()))
        assertEquals("", oidc.cookieHeaderFrom(listOf("=nameless")))
    }

    // -------------------------------------------------------------------------
    // Step 3: exchange
    // -------------------------------------------------------------------------

    @Test
    fun `exchange replays captured cookies as explicit Cookie header and parses top-level refresh`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"user":{"accessToken":"acc-1"},"refreshToken":"ref-1"}""")
                .build(),
        )

        val tokens = oidc.exchange(
            host = host,
            callbackUrl = "adagiostream://oauth?code=CODE123&state=STATE1",
            expectedState = "STATE1",
            verifier = "ver+ifier=1",
            cookieHeader = "auth_method=openid; connect.sid=s%3Aabc123",
        )

        assertEquals("acc-1", tokens.accessToken)
        assertEquals("ref-1", tokens.refreshToken)

        val recorded = server.takeRequest()
        // The exact explicit Cookie header — not a CookieJar round-trip.
        assertEquals("auth_method=openid; connect.sid=s%3Aabc123", recorded.headers["Cookie"])
        assertEquals("/auth/openid/callback", recorded.url.encodedPath)
        assertEquals("STATE1", recorded.url.queryParameter("state"))
        assertEquals("CODE123", recorded.url.queryParameter("code"))
        assertEquals("ver+ifier=1", recorded.url.queryParameter("code_verifier"))
        // Raw wire form keeps the strict encoding.
        assertTrue(recorded.target.contains("code_verifier=ver%2Bifier%3D1"))
    }

    @Test
    fun `exchange parses tokens nested under user and omits Cookie header when none captured`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"user":{"accessToken":"acc-2","refreshToken":"ref-2"}}""")
                .build(),
        )

        val tokens = oidc.exchange(
            host = host,
            callbackUrl = "adagiostream://oauth?code=c2&state=s2",
            expectedState = "s2",
            verifier = "v2",
            cookieHeader = "",
        )

        assertEquals("acc-2", tokens.accessToken)
        assertEquals("ref-2", tokens.refreshToken)
        assertNull(server.takeRequest().headers["Cookie"])
    }

    @Test
    fun `exchange rejects a state mismatch without contacting the server`() = runTest {
        try {
            oidc.exchange(
                host = host,
                callbackUrl = "adagiostream://oauth?code=stolen&state=EVIL",
                expectedState = "SENT",
                verifier = "v",
                cookieHeader = "",
            )
            fail("Expected OidcStateMismatch")
        } catch (e: AudiobookshelfApiException.OidcStateMismatch) {
            // expected
        }
        // No token exchange was attempted.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `exchange rejects a callback missing the code without contacting the server`() = runTest {
        try {
            oidc.exchange(
                host = host,
                callbackUrl = "adagiostream://oauth?state=SENT",
                expectedState = "SENT",
                verifier = "v",
                cookieHeader = "",
            )
            fail("Expected OidcStateMismatch")
        } catch (e: AudiobookshelfApiException.OidcStateMismatch) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `exchange surfaces capped error detail on failure status`() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("No session").build())

        try {
            oidc.exchange(
                host = host,
                callbackUrl = "adagiostream://oauth?code=c&state=s",
                expectedState = "s",
                verifier = "v",
                cookieHeader = "a=1",
            )
            fail("Expected OidcFailed")
        } catch (e: AudiobookshelfApiException.OidcFailed) {
            assertEquals("token exchange", e.step)
            assertEquals(400, e.statusCode)
            assertTrue(e.message!!.contains("No session"))
        }
    }

    @Test
    fun `exchange throws DecodingError when token pair is missing`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"user":{"id":"u1"}}""").build(),
        )

        try {
            oidc.exchange(
                host = host,
                callbackUrl = "adagiostream://oauth?code=c&state=s",
                expectedState = "s",
                verifier = "v",
                cookieHeader = "",
            )
            fail("Expected DecodingError")
        } catch (e: AudiobookshelfApiException.DecodingError) {
            // expected
        }
    }

    // -------------------------------------------------------------------------
    // Callback query parsing (pure)
    // -------------------------------------------------------------------------

    @Test
    fun `parseQuery decodes params and ignores fragments`() {
        val params = oidc.parseQuery("adagiostream://oauth?code=a%2Bb&state=s1&empty=#frag")
        assertEquals("a+b", params["code"])
        assertEquals("s1", params["state"])
        assertEquals("", params["empty"])
        assertTrue(oidc.parseQuery("adagiostream://oauth").isEmpty())
    }
}
