package com.adagiostream.android.ui.screens.audiobooks

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
import com.adagiostream.android.ui.screens.audiobooks.AudiobooksViewModel.LibrariesState
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AudiobooksViewModel picker-vs-direct decision tests (beads_adagio-59p.1.4):
 * 0/1/N book libraries (podcast libraries always excluded), plus the
 * no-account no-op and the 401→reauth error surface.
 *
 * Same architecture as NavidromeLibraryViewModelTest: fake [AccountRepository]
 * flow, real API against [MockWebServer].
 */
class AudiobooksViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AudiobooksViewModel

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

        // Token persistence path reads accounts.value — stub it to the fake flow.
        val accountManager = mockk<AccountManager>(relaxed = true) {
            every { accounts } returns fakeAccountsFlow
        }

        viewModel = AudiobooksViewModel(
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

    private fun mockJson(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    private fun librariesBody(vararg libs: Pair<String, String>): String =
        libs.joinToString(
            prefix = """{"libraries":[""",
            postfix = "]}",
        ) { (id, mediaType) -> """{"id":"$id","name":"Lib $id","mediaType":"$mediaType"}""" }

    // -------------------------------------------------------------------------
    // No account
    // -------------------------------------------------------------------------

    @Test
    fun `api is null and loadLibraries is a no-op without an ABS account`() = runTest {
        viewModel.api.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.loadLibraries()
        assertEquals(LibrariesState.Idle, viewModel.librariesState.value)
    }

    // -------------------------------------------------------------------------
    // 0 / 1 / N book libraries
    // -------------------------------------------------------------------------

    @Test
    fun `zero book libraries resolves Empty - podcast-only server`() = runTest {
        setAbsAccount()
        server.enqueue(mockJson(200, librariesBody("p1" to "podcast", "p2" to "podcast")))

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            assertEquals(LibrariesState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `single book library resolves Single and excludes podcast libraries`() = runTest {
        setAbsAccount()
        server.enqueue(
            mockJson(200, librariesBody("p1" to "podcast", "b1" to "book", "p2" to "podcast")),
        )

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            val state = awaitItem()
            assertTrue("expected Single, got $state", state is LibrariesState.Single)
            assertEquals("b1", (state as LibrariesState.Single).library.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple book libraries resolve Picker with podcasts excluded`() = runTest {
        setAbsAccount()
        server.enqueue(
            mockJson(200, librariesBody("b1" to "book", "p1" to "podcast", "b2" to "book")),
        )

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            val state = awaitItem()
            assertTrue("expected Picker, got $state", state is LibrariesState.Picker)
            assertEquals(listOf("b1", "b2"), (state as LibrariesState.Picker).libraries.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    @Test
    fun `expired session surfaces the reauth-required message`() = runTest {
        setAbsAccount()
        // 401 on the request, then 401 on the token refresh → ReauthRequired.
        server.enqueue(mockJson(401, """{"error":"expired"}"""))
        server.enqueue(mockJson(401, """{"error":"invalid refresh token"}"""))

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            val state = awaitItem()
            assertTrue("expected Error, got $state", state is LibrariesState.Error)
            assertTrue(
                "message should tell the user to sign in again",
                (state as LibrariesState.Error).message.contains("Sign in again"),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server error resolves Error and retry reloads`() = runTest {
        setAbsAccount()
        server.enqueue(mockJson(500, """{"error":"boom"}"""))
        server.enqueue(mockJson(200, librariesBody("b1" to "book")))

        viewModel.librariesState.test(timeout = 10.seconds) {
            assertEquals(LibrariesState.Idle, awaitItem())
            viewModel.loadLibraries()
            assertEquals(LibrariesState.Loading, awaitItem())
            assertTrue(awaitItem() is LibrariesState.Error)

            viewModel.retry()
            assertEquals(LibrariesState.Idle, awaitItem())
            assertEquals(LibrariesState.Loading, awaitItem())
            assertTrue(awaitItem() is LibrariesState.Single)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
