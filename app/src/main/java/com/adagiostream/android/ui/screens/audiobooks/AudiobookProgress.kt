package com.adagiostream.android.ui.screens.audiobooks

import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiException

/**
 * Pure progress/state helpers for the Audiobookshelf browsing UI
 * (beads_adagio-59p.1.4). Kept top-level and side-effect free so the
 * shelf-filter and badge rules are directly unit-testable.
 */

/** Threshold above which a book counts as finished even without the flag. */
private const val FINISHED_PROGRESS = 0.99

/**
 * Loading state shared by the audiobook screens — same transitions as
 * NavidromeLibraryViewModel.LoadState (Idle → Loading → Loaded | Empty |
 * Error), hoisted top-level because three ViewModels share it.
 */
sealed class AbsLoadState {
    /** Initial state before the first load has been requested. */
    data object Idle : AbsLoadState()

    /** A network request is in-flight. */
    data object Loading : AbsLoadState()

    /** Data loaded successfully and is non-empty. */
    data object Loaded : AbsLoadState()

    /** Data loaded successfully but there is nothing to show. */
    data object Empty : AbsLoadState()

    /** The load failed. [message] is user-facing ([userMessage]), never raw. */
    data class Error(val message: String) : AbsLoadState()
}

/** This user's fractional progress (0..1) on the item, 0.0 when unstarted. */
val AbsLibraryItem.bookProgress: Double
    get() = userMediaProgress?.progress ?: 0.0

/**
 * Whether the user has finished this book: the server's `isFinished` flag OR
 * progress >= 0.99 (epsilon per the iOS port — a book parked in the last
 * credits roll reads as finished).
 */
val AbsLibraryItem.isFinishedBook: Boolean
    get() = userMediaProgress?.isFinished == true || bookProgress >= FINISHED_PROGRESS

/**
 * The Continue Listening shelf contents from a `GET /api/me/items-in-progress`
 * response: BOOKS ONLY (`recentEpisode != null` marks a podcast episode) that
 * are started and not finished. Server order (most-recently-listened first)
 * is preserved.
 */
fun continueListeningBooks(items: List<AbsLibraryItem>): List<AbsLibraryItem> =
    items.filter { it.recentEpisode == null && it.bookProgress > 0.0 && !it.isFinishedBook }

/**
 * The progress badge for a book row: `null` when unstarted, "Finished" when
 * [AbsLibraryItem.isFinishedBook], otherwise "N% listened".
 */
fun progressBadge(item: AbsLibraryItem): String? = when {
    item.isFinishedBook -> "Finished"
    item.bookProgress > 0.0 -> "${(item.bookProgress * 100).toInt()}% listened"
    else -> null
}

/** Formats a duration in seconds as "3h 24m" / "52m" (matches the iOS view). */
fun formatBookDuration(seconds: Double): String {
    val s = Math.round(seconds)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * Maps an [AudiobookshelfApiException] to a short user-facing message —
 * mirrors NavidromeApiException.userMessage. [AudiobookshelfApiException
 * .ReauthRequired] is the 401→reauth surface: the token pair was cleared and
 * the user must sign in again from Settings → Accounts.
 */
internal val AudiobookshelfApiException.userMessage: String
    get() = when (this) {
        is AudiobookshelfApiException.ReauthRequired,
        is AudiobookshelfApiException.LoginFailed,
        ->
            "Your session expired. Sign in again in Settings → Accounts."
        is AudiobookshelfApiException.Unreachable ->
            "Cannot reach the server. Check your network and server address."
        is AudiobookshelfApiException.TimedOut ->
            "The request timed out. Check your network connection."
        is AudiobookshelfApiException.ServerError ->
            "Server error (HTTP $statusCode). Try again later."
        is AudiobookshelfApiException.InvalidUrl ->
            "Invalid server URL. Check the address in Settings → Accounts."
        is AudiobookshelfApiException.DecodingError ->
            "Unexpected server response. The server may need updating."
        is AudiobookshelfApiException.SessionNotFound ->
            "Playback session expired. Try again."
    }
