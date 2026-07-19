package com.adagiostream.android.ui.screens.audiobooks

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import com.adagiostream.android.service.player.AudiobookPlaybackLauncher
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AudiobookDetailViewModel tests (beads_adagio-59p.1.4): the Resume/Play
 * bridge — play() hands the ABS account, the loaded item's id, and a null
 * resume override (server /play position) to [AudiobookPlaybackLauncher].
 */
class AudiobookDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AudiobookDetailViewModel

    /** Recorded launcher invocations: (accountId, libraryItemId, resumeOverride). */
    private val launched = mutableListOf<Triple<String, String, Double?>>()
    private val fakeLauncher = AudiobookPlaybackLauncher { account, libraryItemId, resumeOverride ->
        launched += Triple(account.id, libraryItemId, resumeOverride)
    }

    private val fakeAccountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val fakeAccountRepository = object : AccountRepository {
        override val accounts: StateFlow<List<Account>> = fakeAccountsFlow
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"bk1","media":{"metadata":{"title":"Annihilation"}}}""")
                .build(),
        )

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val factory = AudiobookshelfApiFactory { host, username, password, tokens, onTokensChanged ->
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

        val accountManager = mockk<AccountManager>(relaxed = true) {
            every { accounts } returns fakeAccountsFlow
        }

        viewModel = AudiobookDetailViewModel(
            accountRepository = fakeAccountRepository,
            apiProvider = AudiobookshelfApiProvider(
                accountManager = accountManager,
                factory = factory,
            ),
            playbackLauncher = fakeLauncher,
            savedStateHandle = SavedStateHandle(mapOf("itemId" to "bk1")),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `play before load or without account is a no-op`() = runTest {
        viewModel.play() // no account, no item
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `play launches the loaded item through the ABS account with server resume`() = runTest {
        fakeAccountsFlow.value = listOf(
            Account(
                id = "abs-acct",
                name = "My Audiobookshelf",
                type = AccountType.Audiobookshelf(
                    host = server.url("/").toString().trimEnd('/'),
                    username = "alice",
                    accessToken = "acc-1",
                    refreshToken = "ref-1",
                ),
            ),
        )

        viewModel.itemState.test {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.play()

        assertEquals(listOf(Triple("abs-acct", "bk1", null as Double?)), launched)
    }
}
