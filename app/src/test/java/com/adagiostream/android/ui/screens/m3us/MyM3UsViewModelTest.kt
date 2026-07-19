package com.adagiostream.android.ui.screens.m3us

import app.cash.turbine.test
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.testutil.MainDispatcherRule
import com.adagiostream.android.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MyM3UsViewModel tests for the consolidated Custom M3Us tab (59p.5.1):
 * [MyM3UsViewModel.m3uAccounts] filtering and the combined
 * [MyM3UsViewModel.isEmpty] empty-state predicate.
 *
 * Architecture: [AccountManager] and [CustomPlaylistManager] are mocked with
 * MockK, backed by [MutableStateFlow]s — matching the inline-fake convention
 * used by the Navidrome ViewModel tests.
 */
class MyM3UsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val accountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val playlistsFlow = MutableStateFlow<List<CustomPlaylist>>(emptyList())

    private val accountManager = mockk<AccountManager> {
        every { accounts } returns accountsFlow
        coEvery { deleteAccount(any()) } returns Unit
    }
    private val playlistManager = mockk<CustomPlaylistManager> {
        every { playlists } returns playlistsFlow
    }

    private lateinit var viewModel: MyM3UsViewModel

    private val m3uAccount = TestFixtures.makeAccount(id = "m3u-1", name = "My M3U")
    private val xtreamAccount = TestFixtures.makeAccount(
        id = "xtream-1",
        name = "Xtream",
        type = AccountType.XtreamCodes(host = "http://host", username = "u", password = "p"),
    )
    private val subsonicAccount = TestFixtures.makeAccount(
        id = "sub-1",
        name = "Navidrome",
        type = AccountType.Subsonic(host = "http://host", username = "u", password = "p"),
    )

    @Before
    fun setUp() {
        viewModel = MyM3UsViewModel(playlistManager, accountManager)
    }

    // --- m3uAccounts filtering ---

    @Test
    fun `m3uAccounts includes only M3U accounts`() = runTest {
        viewModel.m3uAccounts.test {
            assertEquals(emptyList<Account>(), awaitItem())

            accountsFlow.value = listOf(xtreamAccount, m3uAccount, subsonicAccount)
            assertEquals(listOf(m3uAccount), awaitItem())
        }
    }

    @Test
    fun `m3uAccounts is empty when no accounts are M3U`() = runTest {
        viewModel.m3uAccounts.test {
            assertEquals(emptyList<Account>(), awaitItem())

            accountsFlow.value = listOf(xtreamAccount, subsonicAccount)
            expectNoEvents() // still empty — no new emission for an unchanged filtered list
        }
    }

    @Test
    fun `m3uAccounts updates reactively when an account is removed`() = runTest {
        viewModel.m3uAccounts.test {
            assertEquals(emptyList<Account>(), awaitItem())

            accountsFlow.value = listOf(m3uAccount, xtreamAccount)
            assertEquals(listOf(m3uAccount), awaitItem())

            accountsFlow.value = listOf(xtreamAccount)
            assertEquals(emptyList<Account>(), awaitItem())
        }
    }

    // --- combined empty-state predicate ---

    @Test
    fun `isEmpty is true when both playlists and M3U accounts are empty`() = runTest {
        viewModel.isEmpty.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isEmpty stays true when only non-M3U accounts exist`() = runTest {
        viewModel.isEmpty.test {
            assertTrue(awaitItem())

            accountsFlow.value = listOf(xtreamAccount, subsonicAccount)
            expectNoEvents() // still empty — non-M3U accounts don't count
        }
    }

    @Test
    fun `isEmpty is false when only a playlist exists`() = runTest {
        viewModel.isEmpty.test {
            assertTrue(awaitItem())

            playlistsFlow.value = listOf(CustomPlaylist(name = "Favorites"))
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isEmpty is false when only an M3U account exists`() = runTest {
        viewModel.isEmpty.test {
            assertTrue(awaitItem())

            accountsFlow.value = listOf(m3uAccount)
            assertFalse(awaitItem())
        }
    }

    // --- deleteAccount delegation ---

    @Test
    fun `deleteAccount delegates to AccountManager`() = runTest {
        viewModel.deleteAccount("m3u-1")
        coVerify { accountManager.deleteAccount("m3u-1") }
    }
}
