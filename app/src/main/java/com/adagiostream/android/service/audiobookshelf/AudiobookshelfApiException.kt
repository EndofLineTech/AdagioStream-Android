package com.adagiostream.android.service.audiobookshelf

/**
 * Typed errors from [AudiobookshelfApi] / [AudiobookshelfAuth], mirroring the
 * iOS `AudiobookshelfAPI.APIError` + `AudiobookshelfAuth.AuthError` cases.
 *
 * Each case maps to a distinct failure mode so the UI layer can show precise
 * error copy without matching on raw HTTP codes.
 */
sealed class AudiobookshelfApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The constructed request URL was invalid. */
    class InvalidUrl(url: String) :
        AudiobookshelfApiException("Invalid server URL: $url")

    /** Cannot reach the server (DNS failure / connection refused / no network). */
    class Unreachable(cause: Throwable) :
        AudiobookshelfApiException("Cannot reach the server: ${cause.message}", cause)

    /** The request exceeded the timeout threshold. */
    object TimedOut :
        AudiobookshelfApiException("The request timed out. Check your server address and network.")

    /** `POST /login` returned a non-2xx status — wrong credentials or not an ABS server. */
    class LoginFailed(val statusCode: Int) :
        AudiobookshelfApiException("Login failed (HTTP $statusCode). Check your username and password.")

    /**
     * Token refresh failed (rotation rejected, network error during refresh, or
     * no stored tokens). The token pair has been CLEARED — the caller must
     * route the user back to sign-in.
     */
    object ReauthRequired :
        AudiobookshelfApiException("Your session expired. Please sign in again.")

    /** HTTP response was non-2xx (after any 401→refresh→retry). */
    class ServerError(val statusCode: Int) :
        AudiobookshelfApiException("Server error (HTTP $statusCode).")

    /**
     * `POST /api/session/{id}/sync` returned 404 — the session expired or
     * belongs to another device. Surfaced distinctly so the caller can reopen
     * a session via `/play` instead of treating it as a hard failure.
     */
    object SessionNotFound :
        AudiobookshelfApiException("Playback session not found on the server.")

    /** HTTP 2xx but the body did not decode as the expected shape. */
    class DecodingError(cause: Throwable) :
        AudiobookshelfApiException("Data error: ${cause.message}", cause)

    /**
     * An OIDC/SSO request failed. [step] names which request ("sign-in start",
     * "token exchange"); [detail] is a trimmed, single-lined response-body
     * snippet capped at 200 chars — never the code/verifier/tokens/cookies.
     */
    class OidcFailed(val step: String, val statusCode: Int, detail: String?) :
        AudiobookshelfApiException(
            "SSO sign-in failed at the $step step (HTTP $statusCode)." +
                if (detail.isNullOrEmpty()) "" else " $detail",
        )

    /**
     * The browser callback's `state` did not match the one sent (or the
     * code/state param was missing) — possible CSRF. The code is NEVER
     * exchanged in this case.
     */
    object OidcStateMismatch :
        AudiobookshelfApiException("SSO sign-in failed a security check. Please try again.")

    /** `/auth/openid` returned success but no authorization URL. */
    object OidcAuthUrlMissing :
        AudiobookshelfApiException("The server did not return an SSO sign-in URL.")
}
