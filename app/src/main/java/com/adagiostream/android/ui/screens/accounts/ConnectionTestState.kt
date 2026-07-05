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
