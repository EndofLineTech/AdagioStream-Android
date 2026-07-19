package com.adagiostream.android.service.audiobookshelf

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the [AudiobookshelfApi] for the first enabled
 * [AccountType.Audiobookshelf] account (beads_adagio-59p.1.4).
 *
 * One shared instance per account so every screen (and later the playback
 * branch, 59p.1.5) sees the same auth state: the refresh token ROTATES on
 * every refresh, so two independent API instances would race each other's
 * refresh and one would always lose. Rotated tokens are persisted back to the
 * encrypted accounts store via [AccountManager.updateAccountCredentials]
 * (no channel reload).
 *
 * The cache is keyed on account identity (id + host), NOT on the token pair —
 * a token rotation re-emits [AccountManager.accounts], and rebuilding the API
 * from the stale stored pair mid-flight would discard the live auth state.
 * Account EDITS and DELETES instead evict explicitly via
 * [AccountManager.addAccountEditListener] → [invalidate], so a re-login's
 * fresh tokens are always picked up (and a deleted account leaves no ghost
 * authed API).
 */
@Singleton
class AudiobookshelfApiProvider @Inject constructor(
    private val accountManager: AccountManager,
    private val factory: AudiobookshelfApiFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cachedAccountId: String? = null
    private var cachedHost: String? = null
    private var cached: AudiobookshelfApi? = null

    init {
        // B1: an edited account carries a fresh login's token pair; without
        // eviction the cached instance keeps serving its stale (possibly
        // cleared) auth state forever — or rotates old-but-live tokens over
        // the freshly-stored pair. Rotation persistence
        // (updateAccountCredentials) does NOT fire this listener, so token
        // refreshes keep the same live instance.
        accountManager.addAccountEditListener { invalidate(it) }
    }

    /** Drops the cached API if it belongs to [accountId] (edit/delete eviction). */
    @Synchronized
    fun invalidate(accountId: String) {
        if (cachedAccountId == accountId) {
            cached = null
            cachedAccountId = null
            cachedHost = null
        }
    }

    /**
     * The API for the first enabled Audiobookshelf account in [accounts], or
     * null when none is configured. Call from an `accounts.collect` block —
     * mirrors how NavidromeLibraryViewModel resolves its API.
     */
    @Synchronized
    fun apiFrom(accounts: List<Account>): AudiobookshelfApi? {
        val account = accounts.firstOrNull { it.isEnabled && it.type is AccountType.Audiobookshelf }
        if (account == null) {
            cached = null
            cachedAccountId = null
            cachedHost = null
            return null
        }
        val abs = account.type as AccountType.Audiobookshelf
        cached?.let { existing ->
            if (cachedAccountId == account.id && cachedHost == abs.host) return existing
        }
        val tokens = if (abs.accessToken != null && abs.refreshToken != null) {
            AudiobookshelfAuth.Tokens(accessToken = abs.accessToken, refreshToken = abs.refreshToken)
        } else {
            null
        }
        val accountId = account.id
        return factory.create(
            host = abs.host,
            username = abs.username,
            // No stored password — ABS accounts persist only the token pair.
            password = null,
            tokens = tokens,
            onTokensChanged = { rotated -> persistTokens(accountId, rotated) },
        ).also {
            cached = it
            cachedAccountId = accountId
            cachedHost = abs.host
        }
    }

    /** Writes the rotated (or cleared) token pair back to the encrypted store. */
    private fun persistTokens(accountId: String, tokens: AudiobookshelfAuth.Tokens?) {
        scope.launch {
            val account = accountManager.accounts.value.firstOrNull { it.id == accountId } ?: return@launch
            val abs = account.type as? AccountType.Audiobookshelf ?: return@launch
            accountManager.updateAccountCredentials(
                account.copy(
                    type = abs.copy(
                        accessToken = tokens?.accessToken,
                        refreshToken = tokens?.refreshToken,
                    ),
                ),
            )
        }
    }
}
