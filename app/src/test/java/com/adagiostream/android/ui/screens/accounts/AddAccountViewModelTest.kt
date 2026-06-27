package com.adagiostream.android.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val accountManager = mockk<AccountManager>(relaxed = true) {
        every { accounts } returns MutableStateFlow(emptyList())
    }

    private val navidromeApi = mockk<NavidromeApi>(relaxed = true)
    private val navidromeApiFactory = NavidromeApiFactory { _, _, _ -> navidromeApi }

    private fun createViewModel(
        savedState: Map<String, Any?> = emptyMap(),
    ): AddAccountViewModel {
        val handle = SavedStateHandle(savedState)
        return AddAccountViewModel(accountManager, navidromeApiFactory, handle)
    }

    /** Builds a Subsonic VM pre-populated with valid host/username/password. */
    private fun subsonicViewModel(): AddAccountViewModel {
        val vm = createViewModel()
        vm.setIsSubsonic(true)
        vm.setHost("https://music.example.com")
        vm.setUsername("alice")
        vm.setPassword("sesame")
        return vm
    }

    // --- Validation ---

    @Test
    fun `isValid returns false when name is blank`() {
        val vm = createViewModel()
        vm.setM3uUrl("http://example.com/playlist.m3u")
        assertFalse(vm.isValid())
    }

    @Test
    fun `isValid returns false for M3U when url is blank`() {
        val vm = createViewModel()
        vm.setName("My Account")
        assertFalse(vm.isValid())
    }

    @Test
    fun `isValid returns true for valid M3U`() {
        val vm = createViewModel()
        vm.setName("My Account")
        vm.setM3uUrl("http://example.com/playlist.m3u")
        assertTrue(vm.isValid())
    }

    @Test
    fun `isValid returns false for Xtream when host is blank`() {
        val vm = createViewModel()
        vm.setName("Xtream")
        vm.setIsXtream(true)
        vm.setUsername("user")
        vm.setPassword("pass")
        assertFalse(vm.isValid())
    }

    @Test
    fun `isValid returns false for Xtream when username is blank`() {
        val vm = createViewModel()
        vm.setName("Xtream")
        vm.setIsXtream(true)
        vm.setHost("http://host.com")
        vm.setPassword("pass")
        assertFalse(vm.isValid())
    }

    @Test
    fun `isValid returns false for Xtream when password is blank`() {
        val vm = createViewModel()
        vm.setName("Xtream")
        vm.setIsXtream(true)
        vm.setHost("http://host.com")
        vm.setUsername("user")
        assertFalse(vm.isValid())
    }

    @Test
    fun `isValid returns true for valid Xtream`() {
        val vm = createViewModel()
        vm.setName("Xtream")
        vm.setIsXtream(true)
        vm.setHost("http://host.com")
        vm.setUsername("user")
        vm.setPassword("pass")
        assertTrue(vm.isValid())
    }

    // --- Field setters ---

    @Test
    fun `setName updates name flow`() {
        val vm = createViewModel()
        vm.setName("New Name")
        assertEquals("New Name", vm.name.value)
    }

    @Test
    fun `setIsXtream updates isXtream flow`() {
        val vm = createViewModel()
        vm.setIsXtream(true)
        assertTrue(vm.isXtream.value)
    }

    @Test
    fun `setM3uUrl updates m3uUrl flow`() {
        val vm = createViewModel()
        vm.setM3uUrl("http://url.com/list.m3u")
        assertEquals("http://url.com/list.m3u", vm.m3uUrl.value)
    }

    @Test
    fun `setHost updates host flow`() {
        val vm = createViewModel()
        vm.setHost("http://myhost.com")
        assertEquals("http://myhost.com", vm.host.value)
    }

    @Test
    fun `setEpgUrl updates epgUrl flow`() {
        val vm = createViewModel()
        vm.setEpgUrl("http://epg.com/guide.xml")
        assertEquals("http://epg.com/guide.xml", vm.epgUrl.value)
    }

    // --- Save ---

    @Test
    fun `save sets error when invalid`() {
        val vm = createViewModel()
        vm.save()
        assertEquals("Please fill in all required fields", vm.errorMessage.value)
    }

    @Test
    fun `save calls addAccount for new M3U account`() = runTest {
        val vm = createViewModel()
        vm.setName("My Account")
        vm.setM3uUrl("http://example.com/playlist.m3u")
        vm.save()
        advanceUntilIdle()

        coVerify { accountManager.addAccount(match { it.name == "My Account" }) }
        assertTrue(vm.saveComplete.value)
    }

    @Test
    fun `save calls addAccount for new Xtream account`() = runTest {
        val vm = createViewModel()
        vm.setName("Xtream Account")
        vm.setIsXtream(true)
        vm.setHost("http://host.com")
        vm.setUsername("user")
        vm.setPassword("pass")
        vm.save()
        advanceUntilIdle()

        coVerify {
            accountManager.addAccount(match {
                it.name == "Xtream Account" && it.type is AccountType.XtreamCodes
            })
        }
    }

    // --- Edit mode ---

    @Test
    fun `isEditing is false when no accountId in saved state`() {
        val vm = createViewModel()
        assertFalse(vm.isEditing)
    }

    @Test
    fun `isEditing is true when accountId is in saved state`() {
        val account = Account(
            id = "p1",
            name = "Existing",
            type = AccountType.M3U(url = "http://example.com/list.m3u"),
        )
        every { accountManager.accounts } returns MutableStateFlow(listOf(account))
        val vm = createViewModel(mapOf("accountId" to "p1"))
        assertTrue(vm.isEditing)
    }

    @Test
    fun `edit mode pre-fills M3U fields`() {
        val account = Account(
            id = "p1",
            name = "Existing",
            type = AccountType.M3U(url = "http://example.com/list.m3u", epgUrl = "http://epg.com"),
        )
        every { accountManager.accounts } returns MutableStateFlow(listOf(account))
        val vm = createViewModel(mapOf("accountId" to "p1"))

        assertEquals("Existing", vm.name.value)
        assertEquals("http://example.com/list.m3u", vm.m3uUrl.value)
        assertEquals("http://epg.com", vm.epgUrl.value)
        assertFalse(vm.isXtream.value)
    }

    @Test
    fun `edit mode pre-fills Xtream fields`() {
        val account = Account(
            id = "p2",
            name = "Xtream",
            type = AccountType.XtreamCodes(host = "http://host.com", username = "user", password = "pass"),
        )
        every { accountManager.accounts } returns MutableStateFlow(listOf(account))
        val vm = createViewModel(mapOf("accountId" to "p2"))

        assertEquals("Xtream", vm.name.value)
        assertTrue(vm.isXtream.value)
        assertEquals("http://host.com", vm.host.value)
        assertEquals("user", vm.username.value)
        assertEquals("pass", vm.password.value)
    }

    // --- Error state ---

    @Test
    fun `initial error is null`() {
        val vm = createViewModel()
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun `initial isSaving is false`() {
        val vm = createViewModel()
        assertFalse(vm.isSaving.value)
    }

    // --- Subsonic: type selection + validation ---

    @Test
    fun `setIsSubsonic clears isXtream`() {
        val vm = createViewModel()
        vm.setIsXtream(true)
        vm.setIsSubsonic(true)
        assertTrue(vm.isSubsonic.value)
        assertFalse(vm.isXtream.value)
    }

    @Test
    fun `setIsXtream clears isSubsonic`() {
        val vm = createViewModel()
        vm.setIsSubsonic(true)
        vm.setIsXtream(true)
        assertTrue(vm.isXtream.value)
        assertFalse(vm.isSubsonic.value)
    }

    @Test
    fun `isValid for Subsonic requires host username and password but not name`() {
        val vm = createViewModel()
        vm.setIsSubsonic(true)
        vm.setHost("https://music.example.com")
        vm.setUsername("alice")
        vm.setPassword("sesame")
        // Name intentionally left blank — optional for Subsonic.
        assertTrue(vm.isValid())
    }

    @Test
    fun `isValid for Subsonic is false when password blank`() {
        val vm = createViewModel()
        vm.setIsSubsonic(true)
        vm.setHost("https://music.example.com")
        vm.setUsername("alice")
        assertFalse(vm.isValid())
    }

    // --- Subsonic: Test Connection state machine ---

    @Test
    fun `connectionTestState is initially Idle`() {
        val vm = createViewModel()
        assertEquals(ConnectionTestState.Idle, vm.connectionTestState.value)
    }

    @Test
    fun `testConnection transitions to Success on ping ok`() = runTest {
        coEvery { navidromeApi.ping() } returns Unit
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTestState.Success, vm.connectionTestState.value)
    }

    @Test
    fun `testConnection shows Testing while ping in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { navidromeApi.ping() } coAnswers { gate.await() }
        val vm = subsonicViewModel()
        vm.testConnection()
        // Ping is suspended at the gate — state must read Testing.
        assertEquals(ConnectionTestState.Testing, vm.connectionTestState.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ConnectionTestState.Success, vm.connectionTestState.value)
    }

    @Test
    fun `testConnection maps AuthFailed to incorrect credentials message`() = runTest {
        coEvery { navidromeApi.ping() } throws NavidromeApiException.AuthFailed
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        val state = vm.connectionTestState.value
        assertTrue(state is ConnectionTestState.Error)
        assertEquals("Incorrect username or password", (state as ConnectionTestState.Error).message)
    }

    @Test
    fun `testConnection maps Unreachable to cannot reach server message`() = runTest {
        coEvery { navidromeApi.ping() } throws NavidromeApiException.Unreachable(RuntimeException("no route"))
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        val state = vm.connectionTestState.value
        assertTrue(state is ConnectionTestState.Error)
        assertEquals("Can't reach server — check the URL", (state as ConnectionTestState.Error).message)
    }

    @Test
    fun `testConnection maps TimedOut to cannot reach server message`() = runTest {
        coEvery { navidromeApi.ping() } throws NavidromeApiException.TimedOut
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        val state = vm.connectionTestState.value
        assertTrue(state is ConnectionTestState.Error)
        assertEquals("Can't reach server — check the URL", (state as ConnectionTestState.Error).message)
    }

    @Test
    fun `testConnection maps NotSubsonicServer to not-a-subsonic-server message`() = runTest {
        coEvery { navidromeApi.ping() } throws NavidromeApiException.NotSubsonicServer
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        val state = vm.connectionTestState.value
        assertTrue(state is ConnectionTestState.Error)
        assertEquals(
            "That doesn't look like a Subsonic/Navidrome server",
            (state as ConnectionTestState.Error).message,
        )
    }

    @Test
    fun `testConnection maps ServerError to distinct server-error message`() = runTest {
        coEvery { navidromeApi.ping() } throws NavidromeApiException.ServerError(500)
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        val state = vm.connectionTestState.value
        assertTrue(state is ConnectionTestState.Error)
        assertTrue((state as ConnectionTestState.Error).message.contains("500"))
    }

    @Test
    fun `changing a credential field after success resets connection test to Idle`() = runTest {
        coEvery { navidromeApi.ping() } returns Unit
        val vm = subsonicViewModel()
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTestState.Success, vm.connectionTestState.value)

        vm.setPassword("different")
        assertEquals(ConnectionTestState.Idle, vm.connectionTestState.value)
    }

    // --- Subsonic: save gating ---

    @Test
    fun `save Subsonic is rejected when connection not verified`() = runTest {
        val vm = subsonicViewModel()
        vm.save()
        advanceUntilIdle()
        coVerify(exactly = 0) { accountManager.addAccount(any(), any()) }
        assertNull(vm.saveComplete.value.takeIf { it })
        assertEquals("Test the connection before saving", vm.errorMessage.value)
    }

    @Test
    fun `save Subsonic persists after successful test`() = runTest {
        coEvery { navidromeApi.ping() } returns Unit
        val vm = subsonicViewModel()
        vm.setName("Home Navidrome")
        vm.testConnection()
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        coVerify {
            accountManager.addAccount(
                match {
                    val type = it.type
                    it.name == "Home Navidrome" &&
                        type is AccountType.Subsonic &&
                        type.host == "https://music.example.com"
                },
                any(),
            )
        }
    }

    @Test
    fun `save Subsonic defaults blank name to host`() = runTest {
        coEvery { navidromeApi.ping() } returns Unit
        val vm = subsonicViewModel()
        // Name left blank.
        vm.testConnection()
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        coVerify {
            accountManager.addAccount(
                match { it.name == "music.example.com" && it.type is AccountType.Subsonic },
                any(),
            )
        }
    }

    // --- Subsonic: edit mode ---

    @Test
    fun `edit mode pre-fills Subsonic fields and requires re-test`() {
        val account = Account(
            id = "s1",
            name = "My Navidrome",
            type = AccountType.Subsonic(
                host = "https://nav.example.com",
                username = "bob",
                password = "hunter2",
            ),
        )
        every { accountManager.accounts } returns MutableStateFlow(listOf(account))
        val vm = createViewModel(mapOf("accountId" to "s1"))

        assertEquals("My Navidrome", vm.name.value)
        assertTrue(vm.isSubsonic.value)
        assertEquals("https://nav.example.com", vm.host.value)
        assertEquals("bob", vm.username.value)
        assertEquals("hunter2", vm.password.value)
        // Must re-validate before saving an edit.
        assertEquals(ConnectionTestState.Idle, vm.connectionTestState.value)
    }
}
