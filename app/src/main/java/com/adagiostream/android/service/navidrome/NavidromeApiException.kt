package com.adagiostream.android.service.navidrome

/**
 * Typed errors from [NavidromeApi], mirroring the iOS `APIError` enum.
 *
 * Each case maps to a distinct failure mode in the Subsonic REST protocol so
 * the UI layer can show precise error copy without matching on raw codes.
 */
sealed class NavidromeApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The constructed request URL was invalid. */
    class InvalidUrl(url: String) :
        NavidromeApiException("Invalid server URL: $url")

    /** Cannot reach the server (DNS failure / connection refused / no network). */
    class Unreachable(cause: Throwable) :
        NavidromeApiException("Cannot reach the server: ${cause.message}", cause)

    /** The request exceeded the timeout threshold. */
    object TimedOut :
        NavidromeApiException("The request timed out. Check your server address and network.")

    /** HTTP response was non-2xx. */
    class ServerError(val statusCode: Int) :
        NavidromeApiException("Server error (HTTP $statusCode).")

    /**
     * HTTP 200 but the body is not a Subsonic envelope (HTML error page,
     * wrong server, etc.).
     */
    object NotSubsonicServer :
        NavidromeApiException("The server did not return a Subsonic response. Verify the server URL.")

    /**
     * The server returned `status == "failed"` with a Subsonic error code
     * and message.
     */
    class SubsonicError(val code: Int, override val message: String) :
        NavidromeApiException("Subsonic error $code: $message")

    /**
     * Subsonic error codes 40 or 41 — wrong credentials.
     *
     * Surfaced as a distinct case so the UI can show "wrong credentials" copy
     * without matching on raw numeric codes.
     */
    object AuthFailed :
        NavidromeApiException("Authentication failed. Check your username and password.")

    /** Successfully received a Subsonic envelope but the inner payload did not decode. */
    class DecodingError(cause: Throwable) :
        NavidromeApiException("Data error: ${cause.message}", cause)
}
