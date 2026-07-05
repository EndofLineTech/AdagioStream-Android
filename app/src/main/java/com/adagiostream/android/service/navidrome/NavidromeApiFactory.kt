package com.adagiostream.android.service.navidrome

/**
 * Creates a [NavidromeApi] for a given set of credentials.
 *
 * The host/username/password are only known at the moment the user taps
 * "Test Connection" in the add/edit form, so the API cannot be injected
 * directly. This factory is injected instead (backed by the app's shared
 * OkHttpClient), letting the ViewModel construct a transient API client for a
 * one-shot ping without holding any global state.
 *
 * In tests, a fake factory returns a mocked [NavidromeApi].
 */
fun interface NavidromeApiFactory {
    fun create(host: String, username: String, password: String): NavidromeApi
}
