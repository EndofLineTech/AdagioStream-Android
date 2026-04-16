package com.adagiostream.android.util

import java.net.URI

object UrlSanitizer {

    private val queryParamPattern = Regex(
        """((?:username|password|pass|user|token|key|auth|api_key)=)[^&\s]+""",
        RegexOption.IGNORE_CASE,
    )
    private val pathCredentialPattern = Regex("""/(?:live|movie|series)/[^/]+/[^/]+/""")
    private val basicAuthHeaderPattern = Regex(
        """(Authorization:\s*Basic\s+)\S+""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String): String {
        var result = text
        result = queryParamPattern.replace(result) { "${it.groupValues[1]}****" }
        result = pathCredentialPattern.replace(result) {
            val type = it.value.substringAfter("/").substringBefore("/")
            "/$type/****/****/"
        }
        result = basicAuthHeaderPattern.replace(result) { "${it.groupValues[1]}****" }
        return result
    }

    fun requireHttpUrl(url: String) {
        if (!isHttpUrl(url)) {
            throw IllegalArgumentException("Invalid URL scheme: only http and https are allowed")
        }
    }

    /** Returns true if the URL uses http or https scheme. Safe for logo/artwork URLs that should be silently dropped if invalid. */
    fun isHttpUrl(url: String): Boolean {
        val scheme = try { URI(url).scheme?.lowercase() } catch (_: Exception) { null }
        return scheme == "http" || scheme == "https"
    }
}
