package com.zz.passwordmanager

import com.zz.passwordmanager.data.header.HeaderIntegrityException
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.Session
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultState
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
    // V1-1：VM 现在在 init 里 collect sessionManager.state。relaxed mock 的 state
    // 拿不到可 collect 的 StateFlow，会让 collector 崩——改用真实实例（纯 Kotlin，无 Android 依赖）。
    private val sessionManager = VaultSessionManager()
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
        assertTrue(sessionManager.state.value is VaultState.Unlocked)
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
        assertTrue(sessionManager.state.value is VaultState.Locked)
    }

    // ---- V1-1 FR-8 自动锁定 ----

    @Test
    fun `background longer than one minute locks on foreground`() = runTest(dispatcher) {
        unlockSuccessfully()
        // 直接推进时间：VM 读的是 System.currentTimeMillis，故测真实时长成本高。
        // 这里改为直接验证 AutoLockPolicy 与 sessionManager 的组合语义——
        // sessionManager 用真实实例，构造一个 61 秒前的后台时刻。
        sessionManager.onBackground(System.currentTimeMillis() - 61_000L)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onForeground()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("超期应锁定", viewModel.state.first().locked)
    }

    @Test
    fun `locking clears decrypted entries and revealed plaintext`() = runTest(dispatcher) {
        unlockSuccessfully()
        coEvery { repository.revealPassword("e1") } returns Result.success("secret".toCharArray())
        viewModel.reveal("e1")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.first().revealed.containsKey("e1"))

        sessionManager.lock() // 模拟自动锁定 / 主动锁定
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state.locked)
        assertTrue("明文不得残留", state.revealed.isEmpty())
        assertTrue("锁定后不得保留已解密标题", state.entries.isEmpty())
    }

    @Test
    fun `locking preserves the search query as user intent`() = runTest(dispatcher) {
        unlockSuccessfully()
        viewModel.search("mail")
        dispatcher.scheduler.advanceUntilIdle()

        sessionManager.lock()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("mail", viewModel.state.first().query)
    }

    private suspend fun unlockSuccessfully() {
        stubSearch()
        // Session 的构造器是 internal（:data 边界，:app 不可见）→ 用 mock；
        // relaxed 是因为 lock() 会调 session.clear() 清零 DEK 字节
        coEvery { unlockVault(any()) } returns Result.success(mockk(relaxed = true))
        viewModel.unlock("pw")
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun input() = com.zz.passwordmanager.domain.entries.EntryInput(
        title = "t", username = "u", password = "p".toCharArray(),
        url = null, notes = null, categoryId = null, tags = emptyList(),
    )
}
