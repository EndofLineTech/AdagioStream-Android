package com.adagiostream.android.ui.screens.podcasts

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.account.AccountRepository
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiProvider
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.ui.screens.podcasts.PodcastsViewModel.LibrariesState
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PodcastsViewModel picker-vs-direct decision tests (beads_adagio-59p.2.1):
 * 0/1/N PODCAST libraries (book libraries always excluded) — mirror of
 * AudiobooksViewModelTest with the media-type filter inverted.
 */
class PodcastsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: PodcastsViewModel

    private val fakeAccountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val fakeAccountRepository = object : AccountRepository {
        override val accounts: StateFlow<List<Account>> = fakeAccountsFlow
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

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

        viewModel = PodcastsViewModel(
            accountRepository = fakeAccountRepository,
            apiProvider = AudiobookshelfApiProvider(
                accountManager = accountManager,
                factory = factory,
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun setAbsAccount() {
        fakeAccountsFlow.value = listOf(
            Account(
                id = "test-abs",
                name = "My Audiobookshelf",
                type = AccountType.Audiobookshelf(
                    host = server.url("/").toString().trimEnd('/'),
                    username = "alice",
                    accessToken = "acc-1",
                    refreshToken = "ref-1",
                ),
            ),
        )
    }

    private fun enqueueLibraries(vararg libs: Pair<String, String>) {
        val body = libs.joinToString(
            prefix = """{"libraries":[""",
            postfix = "]}",
        ) { (id, mediaType) -> """{"id":"$id","name":"Lib $id","mediaType":"$mediaType"}""" }
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    @Test
    fun `api is null and loadLibraries is a no-op without an ABS account`() = runTest {
        viewModel.api.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.loadLibraries()
        assertEquals(LibrariesState.Idle, viewModel.librariesState.value)
    }

    @Test
    fun `zero podcast libraries resolves Empty - book-only server`() = runTest {
        setAbsAccount()
        enqueueLibraries("lib1" to "book", "lib2" to "book")

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            assertEquals(LibrariesState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one podcast library resolves Single - book libraries excluded`() = runTest {
        setAbsAccount()
        enqueueLibraries("books" to "book", "pods" to "podcast")

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            val single = awaitItem() as LibrariesState.Single
            assertEquals("pods", single.library.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple podcast libraries resolve Picker`() = runTest {
        setAbsAccount()
        enqueueLibraries("p1" to "podcast", "books" to "book", "p2" to "podcast")

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            val picker = awaitItem() as LibrariesState.Picker
            assertEquals(listOf("p1", "p2"), picker.libraries.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server error surfaces a user-facing message`() = runTest {
        setAbsAccount()
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":"boom"}""").build())

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            val error = awaitItem() as LibrariesState.Error
            assertEquals("Server error (HTTP 500). Try again later.", error.message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
