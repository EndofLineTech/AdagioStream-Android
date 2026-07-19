package com.adagiostream.android.ui.screens.accounts

/**
 * State machine for the Subsonic "Test Connection" flow on the add/edit form.
 *
 * Transitions:
 *   Idle → Testing → (Success | Error)
 *
 * Any change to the server URL / username / password resets the state back to
 * [Idle], forcing a fresh test before the account can be saved.
 */
sealed interface ConnectionTestState {
    /** No test run yet, or inputs changed since the last test. */
    data object Idle : ConnectionTestState

    /** A ping is in flight; inputs should be disabled and a spinner shown. */
    data object Testing : ConnectionTestState

    /** ping.view returned status=ok — the account may now be saved. */
    data object Success : ConnectionTestState

    /** The test failed; [message] is a user-facing, case-specific explanation. */
    data class Error(val message: String) : ConnectionTestState
}

/**
 * State machine for the Audiobookshelf `GET /status` discovery step on the
 * add/edit form: the host is entered first, then discovery decides whether to
 * show local username/password fields and/or the SSO button.
 *
 * Transitions:
 *   Idle → Checking → (Discovered | Error)
 *
 * Any change to the server URL resets the state back to [Idle].
 */
sealed interface AbsDiscoveryState {
    /** No discovery run yet, or the host changed since the last one. */
    data object Idle : AbsDiscoveryState

    /** `GET /status` is in flight. */
    data object Checking : AbsDiscoveryState

    /** Discovery succeeded — [supportsLocal]/[supportsOpenId] drive the form. */
    data class Discovered(
        val supportsLocal: Boolean,
        val supportsOpenId: Boolean,
        val openIdButtonText: String,
    ) : AbsDiscoveryState

    /** Discovery failed; [message] is a user-facing explanation. */
    data class Error(val message: String) : AbsDiscoveryState
}
