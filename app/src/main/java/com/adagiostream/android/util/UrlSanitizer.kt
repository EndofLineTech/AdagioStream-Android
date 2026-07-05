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

    /**
     * Matches Subsonic short-form auth params: u=, t=, s=
     *
     * Uses `[?&](u|t|s)=` to match only as a query param start — avoids
     * over-matching arbitrary params like `type=`, `size=`, `url=`, etc.
     *
     * The leading `[?&]` is captured in group 1 so it can be preserved in
     * the replacement output.
     */
    private val subsonicAuthParamPattern = Regex("""([?&])(u|t|s)=([^&\s]*)""")

    fun redact(text: String): String {
        var result = text
        result = queryParamPattern.replace(result) { "${it.groupValues[1]}****" }
        result = pathCredentialPattern.replace(result) {
            val type = it.value.substringAfter("/").substringBefore("/")
            "/$type/****/****/"
        }
        result = basicAuthHeaderPattern.replace(result) { "${it.groupValues[1]}****" }
        // Redact Subsonic short-form auth params: u=, t=, s=
        result = subsonicAuthParamPattern.replace(result) {
            "${it.groupValues[1]}${it.groupValues[2]}=****"
        }
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
