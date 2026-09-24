package com.zz.passwordmanager

import com.zz.passwordmanager.data.header.HeaderIntegrityException
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.Session
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultStatus
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.platform.AndroidClipboardBridge
import com.zz.passwordmanager.ui.UnlockFormData
import com.zz.passwordmanager.ui.VaultViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T15/T16/T17 VM 层验收：解锁驱动状态、搜索/复制委派、错误入 state（T04 栈实战）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val unlockVault: UnlockVault = mockk()
    private val initializeVault: InitializeVault = mockk()
    private val destroyVault: DestroyVault = mockk(relaxed = true)
    private val repository: EntryRepository = mockk()
    private val clipboard: AndroidClipboardBridge = mockk(relaxed = true)
    private val sessionManager: VaultSessionManager = mockk(relaxed = true)
    private val status: VaultStatus = mockk { every { isInitialized() } returns true }


    private lateinit var viewModel: VaultViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault,
            repository, clipboard, sessionManager, status,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubSearch(vararg rows: com.zz.passwordmanager.domain.entries.EntrySummary) {
        coEvery { repository.search(any(), any(), any()) } returns flow { emit(rows.toList()) }
    }

    @Test
    fun `unlock success flips locked state and refreshes entries`() = runTest(dispatcher) {
        stubSearch()
        // CharArray 无结构化 equals，故用 slot 捕获断言实参内容（而非依赖 eq 匹配）
        val password = slot<CharArray>()
        coEvery { unlockVault(capture(password)) } returns Result.success(mockk<Session>())

        viewModel.unlock("pw")
        dispatcher.scheduler.advanceUntilIdle()

        assertArrayEquals("pw".toCharArray(), password.captured)
        assertFalse(viewModel.state.first().locked)
        verify { sessionManager.begin(any()) }
    }

    @Test
    fun `unlock failure surfaces error message`() = runTest(dispatcher) {
        coEvery { unlockVault(any()) } returns Result.failure(
            com.zz.passwordmanager.data.vault.AuthException(
                com.zz.passwordmanager.data.vault.AuthError.WrongPassword(3),
            ),
        )

        viewModel.unlock("bad")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.first().locked)
        assertTrue(viewModel.state.first().errorMessage != null)
    }

    @Test
    fun `search delegates query and updates entries`() = runTest(dispatcher) {
        stubSearch(com.zz.passwordmanager.domain.entries.EntrySummary("e1", "Site", "u", null, null))

        viewModel.search("site")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertEquals("site", state.query)
        assertEquals(1, state.entries.size)
    }

    @Test
    fun `copy delegates to clipboard bridge`() = runTest(dispatcher) {
        coEvery { repository.revealPassword("e1") } returns Result.success("secret".toCharArray())

        viewModel.copyPassword("e1")
        dispatcher.scheduler.advanceUntilIdle()

        verify { clipboard.copySensitive("secret") }
    }

    @Test
    fun `hide removes revealed plaintext (T17)`() = runTest(dispatcher) {
        coEvery { repository.revealPassword("e1") } returns Result.success("secret".toCharArray())
        viewModel.reveal("e1")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.hide("e1")

        assertFalse(viewModel.state.first().revealed.containsKey("e1"))
    }

    @Test
    fun `save routes create for null id and update otherwise`() = runTest(dispatcher) {
        stubSearch()
        coEvery { repository.create(any()) } returns Result.success("new-id")
        coEvery { repository.update("e1", any()) } returns Result.success(Unit)
        var saved = false

        viewModel.save(null, input(), onSaved = { saved = true })
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.save("e1", input(), onSaved = { saved = saved })
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.create(any()) }
        coVerify(exactly = 1) { repository.update("e1", any()) }
        assertTrue(saved)
    }

    @Test
    fun `initialize failure surfaces error`() = runTest(dispatcher) {
        coEvery { initializeVault(any()) } returns Result.failure(
            com.zz.passwordmanager.data.vault.InitializationException(
                com.zz.passwordmanager.data.vault.InitializationError.AlreadyInitialized,
            ),
        )

        viewModel.initialize(UnlockFormData(masterPassword = "x", confirm = "x"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.first().errorMessage != null)
    }

    // ---- G-B：Header 损坏 → 清除重建入口 ----

    @Test
    fun `header corruption during unlock surfaces corrupted state (G-B)`() = runTest(dispatcher) {
        coEvery { unlockVault(any()) } throws HeaderIntegrityException("MAC mismatch")

        viewModel.unlock("pw")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.first().vaultCorrupted)
        // 损坏不是「密码错误」，不应显示为可重试的表单错误
        assertNull(viewModel.state.first().errorMessage)
    }

    @Test
    fun `destroyCorruptedVault returns to uninitialized state (G-B)`() = runTest(dispatcher) {
        every { status.isInitialized() } returns false
        coEvery { destroyVault() } returns Result.success(Unit)

        viewModel.destroyCorruptedVault()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertFalse(state.initialized)
        assertTrue(state.locked)
        assertFalse(state.vaultCorrupted)
        verify { sessionManager.lock() }
    }

    private fun input() = com.zz.passwordmanager.domain.entries.EntryInput(
        title = "t", username = "u", password = "p".toCharArray(),
        url = null, notes = null, categoryId = null, tags = emptyList(),
    )
}
