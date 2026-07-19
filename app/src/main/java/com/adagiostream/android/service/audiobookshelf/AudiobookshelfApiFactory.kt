package com.adagiostream.android.service.audiobookshelf

/**
 * Creates an [AudiobookshelfApi] for a given account's connection details.
 *
 * Mirrors [com.adagiostream.android.service.navidrome.NavidromeApiFactory]:
 * the host/credentials are only known at runtime (add-account form or a
 * stored account), so the API cannot be injected directly. The factory is
 * injected instead, backed by the app's shared OkHttpClient.
 *
 * @param username null for future SSO accounts.
 * @param password null when only stored tokens are available (no password login).
 * @param tokens the persisted token pair, or null before first login.
 * @param onTokensChanged invoked on EVERY rotation with the new pair (persist
 *   it to the encrypted accounts store immediately) and with null when the
 *   tokens are cleared (re-auth required).
 */
fun interface AudiobookshelfApiFactory {
    fun create(
        host: String,
        username: String?,
        password: String?,
        tokens: AudiobookshelfAuth.Tokens?,
        onTokensChanged: (AudiobookshelfAuth.Tokens?) -> Unit,
    ): AudiobookshelfApi
}
