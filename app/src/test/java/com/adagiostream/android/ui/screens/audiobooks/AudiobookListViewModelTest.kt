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
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AudiobookListViewModel tests (beads_adagio-59p.1.4): book-list state
 * machine plus the Continue Listening shelf pipeline (books only, started
 * and unfinished — the fine-grained boundary cases live in
 * [AudiobookProgressTest]).
 *
 * The server dispatcher is path-keyed because the books and shelf requests
 * run concurrently — enqueue order can't be trusted.
 */
class AudiobookListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AudiobookListViewModel

    private val fakeAccountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val fakeAccountRepository = object : AccountRepository {
        override val accounts: StateFlow<List<Account>> = fakeAccountsFlow
    }

    private val itemsBody = """{"results":[
        {"id":"bk1","media":{"metadata":{"title":"Annihilation","authorName":"Jeff VanderMeer"}}},
        {"id":"bk2","media":{"metadata":{"title":"Borne","authorName":"Jeff VanderMeer"}}}]}"""

    // Shelf: one resumable book, one finished book, one podcast episode.
    private val inProgressBody = """{"libraryItems":[
        {"id":"bk2","media":{"metadata":{"title":"Borne"}},
         "userMediaProgress":{"progress":0.4,"isFinished":false}},
        {"id":"bk9","media":{"metadata":{"title":"Done"}},
         "userMediaProgress":{"progress":0.995,"isFinished":false}},
        {"id":"show1","recentEpisode":{"id":"ep1","title":"Episode 1"},
         "userMediaProgress":{"progress":0.5}}]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/api/libraries/lib1/items" -> mockJson(200, itemsBody)
                    "/api/me/items-in-progress" -> mockJson(200, inProgressBody)
                    else -> mockJson(404, """{"error":"not found"}""")
                }
        }

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

        viewModel = AudiobookListViewModel(
            accountRepository = fakeAccountRepository,
            apiProvider = AudiobookshelfApiProvider(
                accountManager = accountManager,
                factory = factory,
            ),
            savedStateHandle = SavedStateHandle(mapOf("libraryId" to "lib1")),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mockJson(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

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

    @Test
    fun `libraryId comes from the nav arg`() {
        assertEquals("lib1", viewModel.libraryId)
    }

    @Test
    fun `load transitions to Loaded and keeps server book order`() = runTest {
        setAbsAccount()

        viewModel.booksState.test {
            assertEquals(AbsLoadState.Idle, awaitItem())
            viewModel.load()
            assertEquals(AbsLoadState.Loading, awaitItem())
            assertEquals(AbsLoadState.Loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("bk1", "bk2"), viewModel.books.value.map { it.id })
    }

    @Test
    fun `continue listening keeps only started unfinished books`() = runTest {
        setAbsAccount()

        viewModel.continueListening.test {
            assertEquals(emptyList<Any>(), awaitItem())
            viewModel.load()
            // bk9 (>= 0.99) and the podcast episode are filtered out.
            assertEquals(listOf("bk2"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
