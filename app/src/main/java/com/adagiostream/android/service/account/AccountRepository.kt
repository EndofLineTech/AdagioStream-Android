package com.adagiostream.android.service.account

import com.adagiostream.android.model.Account
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the accounts store.
 *
 * Extracted from [AccountManager] so that components that only need to observe
 * accounts (e.g. [NavidromeLibraryViewModel]) can be unit-tested without
 * constructing the full [AccountManager] (which requires an Android Keystore
 * backed [PersistenceService]).
 *
 * [AccountManager] implements this interface directly; callers that need the
 * full write API still inject [AccountManager].
 */
interface AccountRepository {
    /** Reactive list of all configured accounts.  Starts empty until loaded. */
    val accounts: StateFlow<List<Account>>
}
