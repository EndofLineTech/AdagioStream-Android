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
import com.adagiostream.android.service.download.AudiobookDownloadActions
import com.adagiostream.android.service.library.db.AudiobookDownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.player.AudiobookPlaybackLauncher
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.ui.screens.music.DownloadUiState
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

    /** Manifest row backing the download-state flow (59p.1.6). */
    private val downloadRows = MutableStateFlow<AudiobookDownloadEntity?>(null)
    private val downloadCalls = mutableListOf<Pair<String, String>>() // (accountId, itemId)
    private val deleteCalls = mutableListOf<String>()
    private val fakeDownloadActions = object : AudiobookDownloadActions {
        override fun observe(libraryItemId: String): StateFlow<AudiobookDownloadEntity?> = downloadRows
        override fun observeAll(): StateFlow<List<AudiobookDownloadEntity>> = MutableStateFlow(emptyList())
        override suspend fun download(account: Account, libraryItemId: String, title: String?, author: String?) {
            downloadCalls += account.id to libraryItemId
        }
        override suspend fun delete(libraryItemId: String) {
            deleteCalls += libraryItemId
        }
        override fun observeEpisode(showLibraryItemId: String, episodeId: String): StateFlow<AudiobookDownloadEntity?> =
            MutableStateFlow(null)
        override suspend fun downloadEpisode(
            account: Account,
            showLibraryItemId: String,
            episodeId: String,
            title: String?,
            author: String?,
        ) = Unit
        override suspend fun deleteEpisode(showLibraryItemId: String, episodeId: String) = Unit
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
            downloadActions = fakeDownloadActions,
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

    // ---- download affordance (beads_adagio-59p.1.6) ------------------------

    private fun manifestRow(status: String) = AudiobookDownloadEntity(
        id = "bk1",
        accountId = "abs-acct",
        title = "Annihilation",
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `downloadState maps the manifest row to the button state`() = runTest {
        viewModel.downloadState.test {
            assertEquals(DownloadUiState.NOT_DOWNLOADED, awaitItem()) // no row
            downloadRows.value = manifestRow(DownloadStatus.QUEUED)
            assertEquals(DownloadUiState.QUEUED, awaitItem())
            downloadRows.value = manifestRow(DownloadStatus.DOWNLOADING)
            assertEquals(DownloadUiState.DOWNLOADING, awaitItem())
            downloadRows.value = manifestRow(DownloadStatus.COMPLETED)
            assertEquals(DownloadUiState.COMPLETED, awaitItem())
            downloadRows.value = manifestRow(DownloadStatus.FAILED)
            assertEquals(DownloadUiState.FAILED, awaitItem())
            downloadRows.value = null // deleted
            assertEquals(DownloadUiState.NOT_DOWNLOADED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
