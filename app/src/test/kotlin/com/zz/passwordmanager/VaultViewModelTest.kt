package com.zz.passwordmanager

import com.zz.passwordmanager.data.biometric.BiometricToggle
import com.zz.passwordmanager.data.entries.CategoryRepository
import com.zz.passwordmanager.data.biometric.UnlockWithBiometric
import com.zz.passwordmanager.data.header.HeaderIntegrityException
import com.zz.passwordmanager.data.vault.ChangeMasterPassword
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.ReadSecurityQuestions
import com.zz.passwordmanager.data.vault.ResetVault
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.Session
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultState
import com.zz.passwordmanager.data.vault.VaultStatus
import com.zz.passwordmanager.domain.biometric.BiometricGate
import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.preferences.UserPreferences
import com.zz.passwordmanager.domain.security.SecurityScanner
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.password.GeneratorOptions
import com.zz.passwordmanager.platform.AndroidClipboardBridge
import com.zz.passwordmanager.ui.UnlockFormData
import com.zz.passwordmanager.ui.VaultViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val resetVault: ResetVault = mockk(relaxed = true)
    private val changeMasterPassword: ChangeMasterPassword = mockk(relaxed = true)
    private val readSecurityQuestions: ReadSecurityQuestions = mockk(relaxed = true)
    private val unlockWithBiometric: UnlockWithBiometric = mockk(relaxed = true)
    private val biometricToggle: BiometricToggle = mockk(relaxed = true)
    private val gate: BiometricGate = mockk { every { availability() } returns BiometricGate.Availability.AVAILABLE }
    private val repository: EntryRepository = mockk()
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val securityScanner: SecurityScanner = mockk(relaxed = true)
    private val preferences: UserPreferences = mockk(relaxed = true)
    private val clipboard: AndroidClipboardBridge = mockk(relaxed = true)
    // V1-1：VM 现在在 init 里 collect sessionManager.state。relaxed mock 的 state
    // 拿不到可 collect 的 StateFlow，会让 collector 崩——改用真实实例（纯 Kotlin，无 Android 依赖）。
    private val sessionManager = VaultSessionManager()
    private val status: VaultStatus = mockk {
        every { isInitialized() } returns true
        every { isBiometricEnabled() } returns false
    }


    private lateinit var viewModel: VaultViewModel

    /**
     * 用 StateFlow 而非 `flowOf`：VM 在 init 里已 collect 了这个属性，
     * 测试中途再 `every { } returns flowOf(...)` 换掉的是「后续调用」的返回值，
     * 已经收进 collector 的旧流不会重发——改选项类的用例会假绿。
     */
    private val generatorOptionsFlow = MutableStateFlow(GeneratorOptions())

    @Before
    fun setUp() {
        every { preferences.autoLockDuration } returns flowOf(AutoLockDuration.M1)
        every { preferences.defaultPasswordLength } returns flowOf(16)
        every { preferences.generatorOptions } returns generatorOptionsFlow
        Dispatchers.setMain(dispatcher)
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault, resetVault,
            changeMasterPassword, readSecurityQuestions,
            unlockWithBiometric, biometricToggle, gate,
            repository, categoryRepository, securityScanner, preferences, clipboard, sessionManager, status,
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

    /**
     * 回归：先输错一次主密码、再输对——解锁成功后列表页不得残留上一次的错误提示。
     *
     * 此前 `unlock()` 的 onSuccess 只 `sessionManager.begin()`，`errorMessage` 无人清理：
     * `applyLocked()` 在锁定时清（解锁时还没发生过），`dismissError()` 这条路径上没人调，
     * 于是「主密码错误，还可尝试 4 次」被带进已解锁的列表页。
     * 清空点选在 sessionManager collector 的 Unlocked 分支——三条解锁路径的唯一汇合点。
     */
    @Test
    fun `unlock after a wrong attempt clears the previous error message`() = runTest(dispatcher) {
        stubSearch()
        coEvery { unlockVault(any()) } returns Result.failure(AuthException(AuthError.WrongPassword(4)))
        viewModel.unlock("bad")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("前提：先留在错误提示上", "主密码错误，还可尝试 4 次", viewModel.state.first().errorMessage)

        coEvery { unlockVault(any()) } returns Result.success(mockk(relaxed = true))
        viewModel.unlock("pw")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse("前提：应已解锁", viewModel.state.first().locked)
        assertNull("解锁后不得残留上一次的错误提示", viewModel.state.first().errorMessage)
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

    @Test
    fun `successful initialization marks the vault initialized and enters it`() = runTest(dispatcher) {
        // 首次创建时 status.isInitialized() 为 false（VaultUiState 的初值）
        every { status.isInitialized() } returns false
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault, resetVault,
            changeMasterPassword, readSecurityQuestions,
            unlockWithBiometric, biometricToggle, gate,
            repository, categoryRepository, securityScanner, preferences, clipboard, sessionManager, status,
        )
        stubSearch()
        coEvery { initializeVault(any()) } returns Result.success(Unit)
        coEvery { unlockVault(any()) } returns Result.success(mockk(relaxed = true))

        viewModel.initialize(UnlockFormData(masterPassword = "pw", confirm = "pw"))
        dispatcher.scheduler.advanceUntilIdle()

        // 设备走查发现：initialized 只在构造时读一次，首次创建后 UI 仍停在创建表单
        assertTrue(viewModel.state.first().initialized)
        assertFalse(viewModel.state.first().locked)
    }

    /**
     * T40/FR-16：自动锁定档位来自用户偏好，不是写死的常量。
     * 此前 `AUTO_LOCK_DURATION` 是 companion 里的硬编码值，设置项改了也不生效。
     */
    @Test
    fun `auto lock honours the configured duration (T40)`() = runTest(dispatcher) {
        every { preferences.autoLockDuration } returns flowOf(AutoLockDuration.S30)
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault, resetVault,
            changeMasterPassword, readSecurityQuestions,
            unlockWithBiometric, biometricToggle, gate,
            repository, categoryRepository, securityScanner, preferences,
            clipboard, sessionManager, status,
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(AutoLockDuration.S30, viewModel.state.first().autoLockDuration)

        unlockSuccessfully()
        // 20 秒 < 30 秒档位 → 不应锁定（若仍按写死的 1 分钟，这也是「不锁」，故再测反向）
        sessionManager.onBackground(System.currentTimeMillis() - 20_000L)
        viewModel.onForeground()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse("20 秒未达 30 秒档位，不应锁定", viewModel.state.first().locked)

        // 40 秒 > 30 秒档位 → 应锁定（写死 1 分钟时不会锁）
        sessionManager.onBackground(System.currentTimeMillis() - 40_000L)
        viewModel.onForeground()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("40 秒超过 30 秒档位，应锁定", viewModel.state.first().locked)
    }

    // ---- V1-2 FR-12 密保找回 / V1-3 P-07 修改主密码 ----

    @Test
    fun `begin recovery loads question texts and switches to recovery form`() = runTest(dispatcher) {
        every { readSecurityQuestions() } returns listOf("母亲姓名", "第一所学校", "第一只宠物")

        viewModel.beginRecovery()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state.recovering)
        assertEquals(listOf("母亲姓名", "第一所学校", "第一只宠物"), state.recoveryQuestions)
    }

    @Test
    fun `recover vault success returns to unlock form with a success hint`() = runTest(dispatcher) {
        every { readSecurityQuestions() } returns listOf("q1", "q2", "q3")
        coEvery { resetVault(any(), any(), any()) } returns Result.success(Unit)
        viewModel.beginRecovery()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.recoverVault(listOf("a1".toCharArray()), "N3wPass0rd!", "N3wPass0rd!")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertFalse("应退出找回表单", state.recovering)
        assertTrue("应留在锁定态等待用新密码解锁", state.locked)
        assertEquals("主密码已重置，请用新密码解锁", state.successMessage)
    }

    @Test
    fun `recover vault failure surfaces a human readable message`() = runTest(dispatcher) {
        every { readSecurityQuestions() } returns listOf("q1", "q2", "q3")
        coEvery { resetVault(any(), any(), any()) } returns Result.failure(
            AuthException(AuthError.WrongAnswers(attemptsRemaining = 3)),
        )
        viewModel.beginRecovery()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.recoverVault(listOf("bad".toCharArray()), "N3wPass0rd!", "N3wPass0rd!")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("密保答案错误，还可尝试 3 次", viewModel.state.first().errorMessage)
    }

    @Test
    fun `change master password success reports and leaves the settings screen`() = runTest(dispatcher) {
        unlockSuccessfully()
        coEvery { changeMasterPassword(any(), any(), any()) } returns Result.success(Unit)
        var navigatedBack = false

        viewModel.submitMasterPasswordChange("old", "N3wPass0rd!", "N3wPass0rd!") { navigatedBack = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(navigatedBack)
        assertEquals("主密码已修改", viewModel.state.first().successMessage)
    }

    /**
     * 回归（v0.10 实机踩坑）：生物识别解锁成功后必须进入解锁态。
     *
     * 此前 `completeBiometricUnlock` 只清了 authenticating 标志，**没写 locked=false**
     * （主密码那条路径写了），于是指纹验过、会话也建立了，UI 却仍停在解锁屏。
     * 现 locked 只由 sessionManager.state 的 collector 派生，任何解锁路径都自动生效。
     */
    @Test
    fun `biometric unlock success flips locked state via session collector`() = runTest(dispatcher) {
        stubSearch()
        val session = mockk<Session>(relaxed = true)
        coEvery { unlockWithBiometric(any(), any()) } returns Result.success(session)

        viewModel.completeBiometricUnlock(
            mockk<com.zz.passwordmanager.data.biometric.BiometricChallenge>(),
            mockk<javax.crypto.Cipher>(relaxed = true),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse("会话已建立，UI 应进入解锁态", viewModel.state.first().locked)
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

    /** v2.1/F1：档位是 UI 可观察状态，设置页点一下就能重组（此前是普通 var，点不动）。 */
    @Test
    fun `setting auto lock duration writes through to observable state (F1)`() = runTest(dispatcher) {
        every { preferences.autoLockDuration } returns flowOf(AutoLockDuration.S30)
        // setUp 建的是 M1 那份流；换成 S30 后重建，才能断言「状态由流驱动」而非由 setter 实参驱动
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault, resetVault,
            changeMasterPassword, readSecurityQuestions,
            unlockWithBiometric, biometricToggle, gate,
            repository, categoryRepository, securityScanner, preferences,
            clipboard, sessionManager, status,
        )
        coEvery { preferences.setAutoLockDuration(any()) } just runs
        viewModel.setAutoLockDuration(AutoLockDuration.IMMEDIATE)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(AutoLockDuration.S30, viewModel.state.first().autoLockDuration) // 流回写的那份
    }

    /** v2.1/F3：收藏后必须重载详情，否则详情页星标永远停在旧值。 */
    @Test
    fun `toggling favorite reloads the detail so the star flips (F3)`() = runTest(dispatcher) {
        // loadDetail 有锁定守卫（锁定态不写回详情），而收藏本就是解锁态动作，故先解锁
        unlockSuccessfully()
        // EntryDetail 的必填字段：id/title/username/url/notes/categoryId/tags/totpEnabled/createdAt/updatedAt
        // （favorite 与 type 有默认值，此处显式给出以表明测试意图）
        val detail = EntryDetail(
            id = "e1",
            title = "GitHub",
            username = "u",
            url = null,
            notes = null,
            categoryId = null,
            tags = emptyList(),
            totpEnabled = false,
            favorite = false,
            createdAt = 0L,
            updatedAt = 0L,
        )
        coEvery { repository.get("e1") } returns detail
        coEvery { repository.setFavorite("e1", true) } returns Result.success(Unit)

        viewModel.loadDetail("e1")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.detailFor("e1")!!.favorite)

        viewModel.toggleFavorite("e1", true)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(atLeast = 2) { repository.get("e1") }
    }

    /** v2.1/F4：复制要有可见反馈，否则用户不知道到底复制成功没有。 */
    @Test
    fun `copying a password reports success in state (F4)`() = runTest(dispatcher) {
        coEvery { repository.revealPassword("e1") } returns Result.success("s3cret".toCharArray())

        viewModel.copyPassword("e1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(VaultViewModel.COPY_FEEDBACK, viewModel.state.first().successMessage)
    }

    /**
     * 竞态：详情读取在飞行中被锁定（BR-7）。
     *
     * 用 [gate] 把 `repository.get()` 精确卡在挂起点，制造「get 返回 → 写回 `details`」之间
     * 恰好发生锁定的窗口。`applyLocked()` 已经清过一遍，缺守卫的话这次迟到的写回会把
     * 已解密的标题/备注重新塞回锁定态。
     */
    @Test
    fun `locking while detail load is in flight leaves no decrypted detail behind`() = runTest(dispatcher) {
        unlockSuccessfully()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.get("e1") } coAnswers {
            gate.await()
            EntryDetail(
                id = "e1", title = "GitHub", username = "u",
                url = null, notes = null, categoryId = null,
                tags = emptyList(), totpEnabled = false, createdAt = 0L, updatedAt = 0L,
            )
        }

        viewModel.loadDetail("e1")
        dispatcher.scheduler.advanceUntilIdle() // 协程跑到 gate.await() 挂起
        sessionManager.lock()
        dispatcher.scheduler.advanceUntilIdle() // collector 派生 locked，applyLocked() 清 details
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle() // 放行，get() 这时才返回

        assertTrue("前提：应处于锁定态", viewModel.state.first().locked)
        assertNull("锁定后不得把已解密详情写回", viewModel.detailFor("e1"))
    }

    /**
     * 竞态：明文揭示在飞行中被锁定（T17「明文不残留」/ NFR-S4）。
     *
     * 同上编排，但卡的是 `revealPassword()`。`revealed` 是明文密码在本项目里唯一的驻留处，
     * 锁定后把它写回去比写回标题严重得多。
     */
    @Test
    fun `locking while reveal is in flight leaves no plaintext behind`() = runTest(dispatcher) {
        unlockSuccessfully()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.revealPassword("e1") } coAnswers {
            gate.await()
            Result.success("s3cret".toCharArray())
        }

        viewModel.reveal("e1")
        dispatcher.scheduler.advanceUntilIdle() // 协程跑到 gate.await() 挂起
        sessionManager.lock()
        dispatcher.scheduler.advanceUntilIdle() // collector 派生 locked，applyLocked() 清 revealed
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle() // 放行，明文这时才到手

        assertTrue("前提：应处于锁定态", viewModel.state.first().locked)
        assertTrue("锁定后明文密码不得写回", viewModel.state.first().revealed.isEmpty())
    }

    /** v2.1/D4：生成器选项经偏好流回写状态，生成器页与编辑页读同一份。 */
    @Test
    fun `generator options flow into observable state (D4)`() = runTest(dispatcher) {
        val custom = GeneratorOptions(useUpper = false, useLower = true, useDigit = true, useSymbol = false)
        every { preferences.generatorOptions } returns flowOf(custom)
        coEvery { preferences.setGeneratorOptions(any()) } just runs
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault, resetVault,
            changeMasterPassword, readSecurityQuestions,
            unlockWithBiometric, biometricToggle, gate,
            repository, categoryRepository, securityScanner, preferences,
            clipboard, sessionManager, status,
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(custom, viewModel.state.first().generatorOptions)

        viewModel.setGeneratorOptions(GeneratorOptions(excludeAmbiguous = false))
        dispatcher.scheduler.advanceUntilIdle()
        coVerify { preferences.setGeneratorOptions(GeneratorOptions(excludeAmbiguous = false)) }
    }

    /**
     * v2.1：生成器上界收窄为 32 后，升级用户 DataStore 里的越界长度必须在状态边界被收敛——
     * 否则 Task 11 的编辑页一键生成会拿到越界长度而静默失败（generate() 返回 LENGTH_OUT_OF_RANGE），
     * 并弹出与真实原因无关的「请勾选字符集」提示。
     */
    @Test
    fun `stale out of range default length is clamped into state`() = runTest(dispatcher) {
        every { preferences.defaultPasswordLength } returns flowOf(40)
        viewModel = VaultViewModel(
            unlockVault, initializeVault, destroyVault, resetVault,
            changeMasterPassword, readSecurityQuestions,
            unlockWithBiometric, biometricToggle, gate,
            repository, categoryRepository, securityScanner, preferences,
            clipboard, sessionManager, status,
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(32, viewModel.state.first().defaultPasswordLength)
    }

    /** v2.1/D4：编辑页「⚡生成」走全局选项，字符集全关时返回 null 让 UI 提示。 */
    @Test
    fun `generate now uses global options and reports empty char set (D4)`() = runTest(dispatcher) {
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("默认选项应能生成", viewModel.generatePasswordNow()?.isNotBlank() == true)

        val allOff = GeneratorOptions(useUpper = false, useLower = false, useDigit = false, useSymbol = false)
        generatorOptionsFlow.value = allOff
        viewModel.setGeneratorOptions(allOff)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull("字符集全关时不得生成", viewModel.generatePasswordNow())
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
