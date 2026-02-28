package com.adagiostream.android.util

import android.net.Uri

object UrlSanitizer {

    private val queryParamPattern = Regex("""((?:username|password|pass|user|token|key)=)[^&\s]+""", RegexOption.IGNORE_CASE)
    private val pathCredentialPattern = Regex("""/live/[^/]+/[^/]+/""")

    fun redact(text: String): String {
        var result = text
        result = queryParamPattern.replace(result) { "${it.groupValues[1]}****" }
        result = pathCredentialPattern.replace(result, "/live/****/****/")
        return result
    }

    fun requireHttpUrl(url: String) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Invalid URL scheme: only http and https are allowed")
        }
    }
}
