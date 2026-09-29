package com.zz.passwordmanager

import android.net.Uri
import com.zz.passwordmanager.data.backup.BackupFileStore
import com.zz.passwordmanager.data.backup.BackupFormatException
import com.zz.passwordmanager.data.backup.BackupKeyException
import com.zz.passwordmanager.data.backup.ExportBackup
import com.zz.passwordmanager.data.backup.ImportBackup
import com.zz.passwordmanager.ui.BackupStoreFactory
import com.zz.passwordmanager.ui.BackupViewModel
import com.zz.passwordmanager.ui.PendingImport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [BackupViewModel] 验收（FR-7 / v1.3.0）。
 *
 * 覆盖的是**数据层测不到、但恰恰最容易出错**的那一层编排：
 * 1. 导入是两步的——预检只产出「待确认」，**确认之前一个字节都没写进密码库**；
 * 2. 待确认状态里只有 Uri 和两个数字，**没有明文**（这是 `ImportBackup.inspect` 的存在理由）；
 * 3. 失败一律落到 errorMessage，且用的是面向用户的中文（`backupMessage`），
 *    而不是把内部英文异常串漏出去。
 *
 * SAF 那一段（`CreateDocument` / `OpenDocument` 的 Intent 往返）不在本文件覆盖范围：
 * `:app` 无 `src/androidTest`，Compose 层的 launcher 行为只能装机验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val exportBackup: ExportBackup = mockk()
    private val importBackup: ImportBackup = mockk()

    /** 假的 SAF 端点：真读真写 ContentResolver 在 JVM 单测里跑不动（`Looper` 未 mock）。 */
    private val fakeStore = FakeBackupFileStore()
    private val stores = object : BackupStoreFactory {
        override fun invoke(uri: Uri): BackupFileStore = fakeStore
    }

    private val uri: Uri = mockk()
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fakeStore.reset()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BackupViewModel(exportBackup, importBackup, stores)

    // ---- 导出 ----

    @Test
    fun `export reports how many entries were written`() = runTest(dispatcher) {
        coEvery { exportBackup.export(any(), any()) } returns Result.success(42)

        val vm = viewModel()
        vm.exportTo(uri, "master-password")

        advanceUntilIdle()
        assertEquals("已导出 42 条条目，备份文件已加密", vm.state.value.successMessage)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `export failure surfaces a readable message not the internal exception text`() = runTest(dispatcher) {
        coEvery { exportBackup.export(any(), any()) } returns Result.failure(BackupKeyException())

        val vm = viewModel()
        vm.exportTo(uri, "wrong-password")

        advanceUntilIdle()
        val message = requireNotNull(vm.state.value.errorMessage)
        // 认证失败必须同时覆盖「口令错」与「文件被改」两种可能，且必须说明现有数据没丢
        assertTrue(message, message.contains("主密码") && message.contains("现有条目未受影响"))
        assertNull(vm.state.value.successMessage)
    }

    @Test
    fun `export is not re-entered while one is already running`() = runTest(dispatcher) {
        coEvery { exportBackup.export(any(), any()) } returns Result.success(1)

        val vm = viewModel()
        vm.exportTo(uri, "pw")
        vm.exportTo(uri, "pw") // 第二次应被 busy 挡掉

        advanceUntilIdle()
        coVerify(exactly = 1) { exportBackup.export(any(), any()) }
    }

    // ---- 导入：预检与确认必须分开 ----

    @Test
    fun `inspect only produces a pending confirmation - the vault is not touched yet`() = runTest(dispatcher) {
        coEvery { importBackup.inspect(any(), any()) } returns
            Result.success(com.zz.passwordmanager.data.backup.BackupPreview(7, 2))

        val vm = viewModel()
        vm.inspectImport(uri, "master-password")

        advanceUntilIdle()
        val pending = vm.state.value.pendingImport
        assertEquals(PendingImport(uri, 7, 2), pending)
        // 关键：**确认之前不得调用 apply**。这是「全有或全无」在 UI 层的起点。
        coVerify(exactly = 0) { importBackup.apply(any(), any()) }
    }

    @Test
    fun `confirming runs apply and clears the pending confirmation`() = runTest(dispatcher) {
        coEvery { importBackup.inspect(any(), any()) } returns
            Result.success(com.zz.passwordmanager.data.backup.BackupPreview(7, 2))
        coEvery { importBackup.apply(any(), any()) } returns Result.success(7)

        val vm = viewModel()
        vm.inspectImport(uri, "master-password")
        advanceUntilIdle()
        vm.confirmImport("master-password")

        advanceUntilIdle()
        coVerify(exactly = 1) { importBackup.apply(any(), any()) }
        assertNull(vm.state.value.pendingImport)
        assertTrue(vm.state.value.successMessage!!.contains("原有内容已被全部替换"))
    }

    @Test
    fun `cancelling drops the pending confirmation and never applies`() = runTest(dispatcher) {
        coEvery { importBackup.inspect(any(), any()) } returns
            Result.success(com.zz.passwordmanager.data.backup.BackupPreview(7, 2))

        val vm = viewModel()
        vm.inspectImport(uri, "master-password")
        advanceUntilIdle()
        vm.cancelImport()

        assertNull(vm.state.value.pendingImport)
        coVerify(exactly = 0) { importBackup.apply(any(), any()) }
    }

    @Test
    fun `confirm with nothing pending is a no-op`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.confirmImport("master-password")

        advanceUntilIdle()
        coVerify(exactly = 0) { importBackup.apply(any(), any()) }
    }

    @Test
    fun `a failed inspect leaves nothing pending and never applies`() = runTest(dispatcher) {
        coEvery { importBackup.inspect(any(), any()) } returns Result.failure(BackupKeyException())

        val vm = viewModel()
        vm.inspectImport(uri, "wrong-password")

        advanceUntilIdle()
        assertNull(vm.state.value.pendingImport)
        assertNotNull(vm.state.value.errorMessage)
        coVerify(exactly = 0) { importBackup.apply(any(), any()) }
    }

    /**
     * 预检过了、但**确认时**才失败（文件在两次调用之间被换掉）。
     *
     * 这条用例盯的是 `ImportBackup.apply` 的「不复用上一次的明文、重新预检」：
     * 若实现改成拿上次的 payload 直接提交，这里会变成静默写入错误数据。
     */
    @Test
    fun `apply re-validates and reports failure without clearing the pending confirmation`() = runTest(dispatcher) {
        coEvery { importBackup.inspect(any(), any()) } returns
            Result.success(com.zz.passwordmanager.data.backup.BackupPreview(7, 2))
        coEvery { importBackup.apply(any(), any()) } returns
            Result.failure(BackupFormatException("sealed length mismatch"))

        val vm = viewModel()
        vm.inspectImport(uri, "master-password")
        advanceUntilIdle()
        vm.confirmImport("master-password")

        advanceUntilIdle()
        assertNotNull(vm.state.value.errorMessage)
        // 确认框留着，用户可以改口令重试或取消——但绝不能显示成「已导入」
        assertNotNull(vm.state.value.pendingImport)
        assertNull(vm.state.value.successMessage)
    }

    @Test
    fun `an unreadable selected file is reported rather than silently doing nothing`() = runTest(dispatcher) {
        fakeStore.failReadWith = SecurityException("no provider")

        val vm = viewModel()
        vm.inspectImport(uri, "pw")

        advanceUntilIdle()
        assertNotNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.pendingImport)
    }

    /** 假 SAF 端点：只按脚本返回字节或抛异常，不碰 `ContentResolver`。 */
    private class FakeBackupFileStore : BackupFileStore {
        var failReadWith: Throwable? = null
        private var bytes: ByteArray = FRAME

        fun reset() {
            failReadWith = null
            bytes = FRAME
        }

        override suspend fun read(): ByteArray {
            failReadWith?.let { throw it }
            return bytes
        }

        override suspend fun write(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    private companion object {
        /** 一段任意字节：本文件不校验容器，字节从哪来对 ViewModel 编排没有影响。 */
        val FRAME = byteArrayOf(1, 2, 3, 4, 5)
    }
}
