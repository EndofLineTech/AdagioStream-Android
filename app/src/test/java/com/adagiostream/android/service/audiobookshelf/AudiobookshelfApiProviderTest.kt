package com.adagiostream.android.service.audiobookshelf

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * AudiobookshelfApiProvider cache-lifecycle tests (beads_adagio-59p.1.4 B1):
 * rotation re-emits keep the same instance; account edits/deletes evict via
 * the [AccountManager.addAccountEditListener] hook so a re-login's fresh
 * tokens are picked up; rotation persistence writes through
 * [AccountManager.updateAccountCredentials] (never [AccountManager
 * .updateAccount], which would evict and reload channels).
 *
 * No network — hosts are plain strings and token state is inspected via
 * [AudiobookshelfAuth.currentAccessToken].
 */
class AudiobookshelfApiProviderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val client = OkHttpClient()

    /** onTokensChanged callbacks in creation order, to drive rotations. */
    private val tokenCallbacks = mutableListOf<(AudiobookshelfAuth.Tokens?) -> Unit>()

    private val factory = AudiobookshelfApiFactory { host, username, password, tokens, onTokensChanged ->
        tokenCallbacks += onTokensChanged
        AudiobookshelfApi(
            client = client,
            host = host,
            auth = AudiobookshelfAuth(
                client = client,
                host = host,
                username = username,
                password = password,
                initialTokens = tokens,
                onTokensChanged = onTokensChanged,
            ),
        )
    }

    private val accountsFlow = MutableStateFlow<List<Account>>(emptyList())

    /** The listener the provider registered — invoking it simulates updateAccount/deleteAccount. */
    private val editListener = slot<(String) -> Unit>()

    private val accountManager = mockk<AccountManager>(relaxed = true) {
        every { accounts } returns accountsFlow
        every { addAccountEditListener(capture(editListener)) } just Runs
    }

    private val provider = AudiobookshelfApiProvider(accountManager, factory)

    private fun account(
        id: String = "abs-1",
        host: String = "http://abs.example",
        accessToken: String? = "acc-1",
        refreshToken: String? = "ref-1",
    ): Account = Account(
        id = id,
        name = "ABS",
        type = AccountType.Audiobookshelf(
            host = host,
            username = "alice",
            accessToken = accessToken,
            refreshToken = refreshToken,
        ),
    )

    @Test
    fun `re-emits with the same account keep the same instance - even after token rotation`() {
        val api1 = provider.apiFrom(listOf(account(accessToken = "acc-1")))
        // Rotation persisted → accounts re-emits with the NEW stored pair.
        val api2 = provider.apiFrom(listOf(account(accessToken = "acc-2", refreshToken = "ref-2")))

        assertSame(api1, api2)
        // The live instance keeps its own auth state — not rebuilt from the store.
        assertEquals("acc-1", api1!!.auth.currentAccessToken())
    }

    @Test
    fun `edit eviction - re-login tokens are picked up by a fresh instance`() {
        // Refresh token died: instance cleared its tokens (ReauthRequired loop).
        val api1 = provider.apiFrom(listOf(account(accessToken = null, refreshToken = null)))
        assertNull(api1!!.auth.currentAccessToken())

        // User edits the account; fresh login mints new tokens; updateAccount
        // fires the edit listener, then the accounts flow re-emits.
        editListener.captured.invoke("abs-1")
        val api2 = provider.apiFrom(listOf(account(accessToken = "acc-new", refreshToken = "ref-new")))

        assertNotSame(api1, api2)
        assertEquals("acc-new", api2!!.auth.currentAccessToken())
    }

    @Test
    fun `host change evicts the cached instance`() {
        val api1 = provider.apiFrom(listOf(account(host = "http://a.example")))
        val api2 = provider.apiFrom(listOf(account(host = "http://b.example")))

        assertNotSame(api1, api2)
        assertEquals("http://b.example", api2!!.hostBase)
    }

    @Test
    fun `rotation persists through updateAccountCredentials only`() = runTest {
        accountsFlow.value = listOf(account())
        provider.apiFrom(accountsFlow.value)

        tokenCallbacks.last().invoke(AudiobookshelfAuth.Tokens("acc-9", "ref-9"))

        coVerify(exactly = 1) {
            accountManager.updateAccountCredentials(
                match {
                    val abs = it.type as AccountType.Audiobookshelf
                    it.id == "abs-1" && abs.accessToken == "acc-9" && abs.refreshToken == "ref-9"
                },
            )
        }
        // Never the evicting/channel-reloading path.
        coVerify(exactly = 0) { accountManager.updateAccount(any()) }
    }

    @Test
    fun `deletion clears the cache entry - no ghost authed API`() {
        val api1 = provider.apiFrom(listOf(account()))

        // deleteAccount fires the edit listener, then re-emits without the account.
        editListener.captured.invoke("abs-1")
        assertNull(provider.apiFrom(emptyList()))

        // Re-adding the same id+host later builds a fresh instance.
        val api2 = provider.apiFrom(listOf(account()))
        assertNotSame(api1, api2)
    }

    @Test
    fun `invalidate ignores other account ids`() {
        val api1 = provider.apiFrom(listOf(account()))
        provider.invalidate("some-other-account")
        assertSame(api1, provider.apiFrom(listOf(account())))
    }
}
