package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audiobookshelf OpenID Connect (SSO) sign-in — port of iOS
 * `AudiobookshelfOIDC` / `AudiobookshelfOIDCFlow`. ABS fronts the identity
 * provider (Google / Authentik / Authelia / …), so one PKCE
 * authorization-code flow covers every backend.
 *
 * The three steps:
 *  1. [startAuthorization] — `GET /auth/openid` with redirects DISABLED; the
 *     IdP authorization URL is read from the 3xx `Location` header (the
 *     official audiobookshelf-app sets `redirect: 'manual'` and does the
 *     same). All `Set-Cookie` headers are captured for explicit replay.
 *  2. The caller opens the authorization URL in a Chrome Custom Tab; the IdP
 *     redirects to `adagiostream://oauth?code=..&state=..` which routes back
 *     via the app's intent filter.
 *  3. [exchange] — `GET /auth/openid/callback` with the captured cookies sent
 *     as an explicit `Cookie` header (a CookieJar is NOT trusted to replay
 *     them — iOS's cookie store silently dropped these, causing 400
 *     "No session"). The response is the same shape as `POST /login`.
 *
 * This type only OBTAINS the first token pair; refresh/rotation/401-retry is
 * [AudiobookshelfAuth]'s job once the pair is persisted.
 *
 * SECURITY: never log the code, verifier, state, tokens, cookies, or the full
 * callback URL — they are all secrets.
 */
@Singleton
class AudiobookshelfOidc @Inject constructor(client: OkHttpClient) {

    /**
     * Dedicated client with redirects disabled, derived via `newBuilder()` so
     * the shared client keeps following redirects everywhere else. Following
     * `/auth/openid`'s 302 to the IdP would download the sign-in HTML instead
     * of handing the URL to the browser.
     */
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** A PKCE pair. [verifier] is the secret; challenge = base64url(SHA256(verifier)). */
    data class Pkce(val verifier: String, val challenge: String) {
        /** Verifier intentionally excluded to prevent accidental log exposure. */
        override fun toString(): String = "Pkce(****)"
    }

    /**
     * Result of [startAuthorization]: the IdP URL to open in the browser plus
     * the `Cookie` request-header value to replay on [exchange].
     */
    data class Authorization(val authorizeUrl: String, val cookieHeader: String) {
        /** Cookie values are session secrets — excluded from toString. */
        override fun toString(): String = "Authorization(****)"
    }

    // -------------------------------------------------------------------------
    // Step 1: authorization URL + cookie capture
    // -------------------------------------------------------------------------

    /**
     * `GET /auth/openid?code_challenge=..&code_challenge_method=S256&
     * redirect_uri=..&client_id=..&response_type=code&state=..` without
     * following redirects. Any 3xx with a `Location` header is the success
     * case; a 2xx JSON body (`authorizationUrl` | `authorization_url` | `url`)
     * is the defensive fallback some deployments use.
     */
    suspend fun startAuthorization(
        host: String,
        challenge: String,
        state: String,
    ): Authorization = withContext(Dispatchers.IO) {
        val url = buildAuthorizeUrl(host, challenge, state)
            ?: throw AudiobookshelfApiException.InvalidUrl(host)
        perform(Request.Builder().url(url).get().build()).use { resp ->
            // Capture ALL Set-Cookie headers (auth_method, connect.sid, …) —
            // ABS needs them back on /auth/openid/callback or it 400s with
            // "No session".
            val cookieHeader = cookieHeaderFrom(resp.headers.values("Set-Cookie"))
            if (resp.code in 300..399) {
                val location = resp.header("Location")
                if (!location.isNullOrBlank()) {
                    return@use Authorization(location, cookieHeader)
                }
                // 3xx without Location — fall through to the body fallback.
            }
            val body = resp.body.string()
            if (resp.code !in 200..399) {
                throw AudiobookshelfApiException.OidcFailed(
                    step = "sign-in start",
                    statusCode = resp.code,
                    detail = safeErrorDetail(body),
                )
            }
            val fromBody = authorizeUrlFromBody(body)
                ?: throw AudiobookshelfApiException.OidcAuthUrlMissing
            Authorization(fromBody, cookieHeader)
        }
    }

    /**
     * Builds the `/auth/openid` URL. Query VALUES are pre-encoded with
     * [encodeQueryValue] so `+`, `/`, and `=` are always percent-encoded —
     * express's `qs` decoder turns a literal `+` into a space server-side, a
     * bug iOS burned a fix on. `addEncodedQueryParameter` passes the escapes
     * through untouched.
     */
    internal fun buildAuthorizeUrl(host: String, challenge: String, state: String): HttpUrl? {
        val base = AudiobookshelfUrl.resolve(host, "/auth/openid") ?: return null
        val builder = base.newBuilder()
        linkedMapOf(
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "state" to state,
        ).forEach { (name, value) ->
            builder.addEncodedQueryParameter(name, encodeQueryValue(value))
        }
        return builder.build()
    }

    /**
     * Extracts the authorization URL from a `/auth/openid` response body: a
     * JSON object with one of several key spellings, or a bare URL string.
     */
    internal fun authorizeUrlFromBody(body: String): String? {
        val obj = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (obj != null) {
            return listOf("authorizationUrl", "authorization_url", "url")
                .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
        }
        return body.trim().takeIf { it.startsWith("http") }
    }

    // -------------------------------------------------------------------------
    // Step 3: token exchange
    // -------------------------------------------------------------------------

    /**
     * Validates the browser callback and exchanges the code:
     * `GET /auth/openid/callback?state=..&code=..&code_verifier=..` with the
     * captured cookies as an explicit `Cookie` header.
     *
     * The callback's `state` MUST equal [expectedState] — otherwise a crafted
     * `adagiostream://oauth?code=…` on the app-wide scheme could inject an
     * attacker's authorization code (PKCE alone does not cover this). On a
     * mismatch or missing params, [AudiobookshelfApiException.OidcStateMismatch]
     * is thrown BEFORE any request is made.
     */
    suspend fun exchange(
        host: String,
        callbackUrl: String,
        expectedState: String,
        verifier: String,
        cookieHeader: String,
    ): AudiobookshelfAuth.Tokens = withContext(Dispatchers.IO) {
        val params = parseQuery(callbackUrl)
        val code = params["code"]
        if (code.isNullOrEmpty() || params["state"] != expectedState) {
            throw AudiobookshelfApiException.OidcStateMismatch
        }

        val base = AudiobookshelfUrl.resolve(host, "/auth/openid/callback")
            ?: throw AudiobookshelfApiException.InvalidUrl(host)
        val url = base.newBuilder()
            .addEncodedQueryParameter("state", encodeQueryValue(expectedState))
            .addEncodedQueryParameter("code", encodeQueryValue(code))
            .addEncodedQueryParameter("code_verifier", encodeQueryValue(verifier))
            .build()

        val builder = Request.Builder().url(url).get()
        if (cookieHeader.isNotEmpty()) {
            builder.header("Cookie", cookieHeader)
        }

        perform(builder.build()).use { resp ->
            val body = resp.body.string()
            if (resp.code !in 200..299) {
                throw AudiobookshelfApiException.OidcFailed(
                    step = "token exchange",
                    statusCode = resp.code,
                    detail = safeErrorDetail(body),
                )
            }
            // Same LoginResponse shape as POST /login: accessToken under
            // user.*, refreshToken top-level or under user.*.
            val payload = try {
                json.decodeFromString<AbsLoginResponse>(body)
            } catch (e: Exception) {
                throw AudiobookshelfApiException.DecodingError(e)
            }
            val access = payload.user?.accessToken
            val refresh = payload.refreshToken ?: payload.user?.refreshToken
            if (access == null || refresh == null) {
                throw AudiobookshelfApiException.DecodingError(
                    IllegalStateException("Callback response missing token pair"),
                )
            }
            AudiobookshelfAuth.Tokens(accessToken = access, refreshToken = refresh)
        }
    }

    // -------------------------------------------------------------------------
    // Pure helpers (unit-tested without Android)
    // -------------------------------------------------------------------------

    /**
     * Builds a `Cookie` request-header value (`name=value; name=value`) from
     * raw `Set-Cookie` header values, dropping attributes (Path, HttpOnly, …)
     * and malformed entries. Empty input yields "". The RESULT contains
     * session secrets — never log it.
     */
    internal fun cookieHeaderFrom(setCookieHeaders: List<String>): String =
        setCookieHeaders
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') && it.substringBefore('=').isNotBlank() }
            .joinToString("; ")

    /** Parses the query of a callback URL into a name→value map (form-decoded). */
    internal fun parseQuery(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
    }

    /**
     * Short, safe error snippet from a failed-response body: trimmed,
     * single-lined, capped at 200 chars + "…". Never called on a success
     * body, so it can't leak a token payload.
     */
    internal fun safeErrorDetail(body: String): String? {
        val oneLine = body.trim().replace('\n', ' ').replace('\r', ' ')
        if (oneLine.isEmpty()) return null
        return if (oneLine.length > 200) oneLine.take(200) + "…" else oneLine
    }

    /** Executes on the no-redirect client, mapping transport errors to typed exceptions. */
    private fun perform(request: Request): Response = try {
        noRedirectClient.newCall(request).execute()
    } catch (e: SocketTimeoutException) {
        throw AudiobookshelfApiException.TimedOut
    } catch (e: IOException) {
        throw AudiobookshelfApiException.Unreachable(e)
    }

    companion object {
        /** Custom scheme the intent filter on MainActivity is registered for. */
        const val REDIRECT_URI = "adagiostream://oauth"
        const val CLIENT_ID = "AdagioStream"

        private val secureRandom = SecureRandom()

        /**
         * Fresh PKCE pair: 32 random bytes → 43-char base64url verifier
         * (within RFC 7636's 43–128 range); challenge = base64url(SHA256(verifier)).
         */
        fun makePkce(): Pkce {
            val verifier = randomToken()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier = verifier, challenge = base64Url(digest))
        }

        /** Random CSRF `state`: 32 random bytes as base64url. */
        fun makeState(): String = randomToken()

        /**
         * Percent-encodes a query VALUE so `+`, `/`, and `=` never appear
         * literally (express's qs decoder turns a literal `+` into a space).
         * Space becomes `%20`, not `+`, for the same reason.
         */
        internal fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun randomToken(): String =
            base64Url(ByteArray(32).also { secureRandom.nextBytes(it) })

        /** base64url per RFC 4648 §5, padding stripped. */
        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
