package com.adagiostream.android.service.navidrome

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Builds Subsonic REST API authentication query parameters using token auth.
 *
 * Token auth is the only mode implemented. Legacy `p=enc` (plain/hex-encoded
 * password) is intentionally omitted — deferred per PO decision.
 *
 * The Subsonic protocol spec requires MD5 for token computation:
 *   token = MD5(password + salt)   (lowercase hex)
 * This is NOT a security choice; it is a spec-compliance requirement.
 * The server verifies `t == MD5(password + salt)` on every request.
 *
 * Per-request salt is generated using [SecureRandom] (cryptographically secure).
 * An injectable [salt] parameter is provided so tests can supply deterministic
 * values without touching the PRNG.
 *
 * The password is intentionally excluded from [toString] to prevent accidental
 * log exposure.
 */
class SubsonicAuth(
    val username: String,
    private val password: String,
) {
    companion object {
        /** Client identifier sent as the `c` parameter on every request. */
        const val CLIENT_NAME = "AdagioStream"

        /** Subsonic REST API version sent as the `v` parameter. */
        const val API_VERSION = "1.16.1"

        /** Default salt length in bytes. 12 bytes = 24 hex chars, well above the 8-byte minimum. */
        private const val DEFAULT_SALT_BYTE_COUNT = 12

        /**
         * Computes `MD5(password + salt)` as a lowercase hex string.
         *
         * [MessageDigest.getInstance] with "MD5" is always available on Android/JVM.
         * The Subsonic spec mandates MD5; there is no alternative within the protocol.
         */
        fun md5Token(password: String, salt: String): String {
            val input = (password + salt).toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("MD5").digest(input)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Generates a cryptographically-secure random salt encoded as lowercase hex.
         * Minimum [byteCount] is 8 (spec requirement); default is 12.
         * Uses [SecureRandom] — never [kotlin.random.Random] or [java.util.Random].
         */
        private fun generateSalt(byteCount: Int = DEFAULT_SALT_BYTE_COUNT): String {
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Returns the six Subsonic authentication query parameters as a [Map].
     *
     * Keys: `u`, `t`, `s`, `c`, `v`, `f`
     *
     * @param salt Optional salt for deterministic testing. Production callers
     *   should always pass `null` (the default) to get a fresh secure salt.
     */
    fun queryParams(salt: String? = null): Map<String, String> {
        val resolvedSalt = salt ?: generateSalt()
        val token = md5Token(password = password, salt = resolvedSalt)
        return mapOf(
            "u" to username,
            "t" to token,
            "s" to resolvedSalt,
            "c" to CLIENT_NAME,
            "v" to API_VERSION,
            "f" to "json",
        )
    }

    /** Password intentionally excluded to prevent accidental log exposure. */
    override fun toString(): String = "SubsonicAuth(username=$username)"
}
