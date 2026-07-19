package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the `adagiostream://oauth?code=..&state=..` redirect from
 * [com.adagiostream.android.MainActivity]'s intent filter to whichever
 * ViewModel is running the OIDC flow. A StateFlow (not a SharedFlow) so a
 * callback that lands before the collector resubscribes after the Custom Tab
 * round-trip is not lost — it is held until [consume].
 *
 * The URL contains the authorization code — never log it.
 */
@Singleton
class AudiobookshelfOidcCallbackBus @Inject constructor() {

    private val _callbackUrl = MutableStateFlow<String?>(null)
    val callbackUrl: StateFlow<String?> = _callbackUrl.asStateFlow()

    fun publish(url: String) {
        _callbackUrl.value = url
    }

    fun consume() {
        _callbackUrl.value = null
    }
}
