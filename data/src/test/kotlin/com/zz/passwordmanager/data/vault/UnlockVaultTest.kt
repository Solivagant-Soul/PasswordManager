package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

/** T07 验收：正确解锁/错误计数/限流锁定（feature 02 映射；纯 JVM，fake clock 精确推进）。 */
class UnlockVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val headerStore get() = VaultHeaderStore(tmp.root, macKey)

    private fun throttle() = AuthThrottle({ now })

    private suspend fun initialize() {
        assertTrue(
            InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
                request("Passw0rd!主密码"),
            ).isSuccess,
        )
    }

    private fun request(password: String) = InitializeRequest(
        masterPassword = password.toCharArray(),
        confirmMasterPassword = password.toCharArray(),
        securityQuestions = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3").map {
            InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
        },
    )

    @Test
    fun `correct password unlocks with session carrying dek`() = runTest {
        initialize()

        val result = UnlockVault(keyManager, headerStore, throttle())("Passw0rd!主密码".toCharArray())

        val session = result.getOrThrow()
        assertEquals(32, session.dek.size)
        assertEquals(0, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `wrong password is rejected and counted`() = runTest {
        initialize()

        val result = UnlockVault(keyManager, headerStore, throttle())("wrong-pass".toCharArray())

        val error = (result.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.WrongPassword(attemptsRemaining = 4), error)
        assertEquals(1, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `fifth failure locks for one minute`() = runTest {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())

        repeat(4) { unlock("wrong".toCharArray()) }
        val fifth = unlock("wrong".toCharArray())

        val error = (fifth.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.LockedUntil(60_000L), error)
        assertEquals(now + 60_000L, headerStore.read()!!.lockUntilEpochMs)
    }

    @Test
    fun `locked vault rejects even correct password with remaining time`() = runTest {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())
        repeat(5) { unlock("wrong".toCharArray()) }
        now += 30_000L // 半分钟后再试

        val result = unlock("Passw0rd!主密码".toCharArray())

        val error = (result.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.LockedUntil(30_000L), error)
    }

    @Test
    fun `lock expires after one minute`() = runTest {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())
        repeat(5) { unlock("wrong".toCharArray()) }
        now += 60_000L // 恰好 1 分钟后

        val result = unlock("Passw0rd!主密码".toCharArray())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `uninitialized vault is rejected`() = runTest {
        val result = UnlockVault(keyManager, headerStore, throttle())("anything".toCharArray())

        assertEquals(AuthError.VaultMissing, (result.exceptionOrNull() as AuthException).error)
    }

    @Test
    fun `successful unlock resets failure counter`() = runTest {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())
        repeat(3) { unlock("wrong".toCharArray()) }
        assertTrue(unlock("Passw0rd!主密码".toCharArray()).isSuccess)

        assertEquals(0, headerStore.read()!!.failedAttempts)
    }

    // ---- 评审 G-A：并发尝试不得丢失败计数 ----

    @Test
    fun `concurrent failures are all counted without lost updates`() = runTest {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())

        // 三次并发失败（真实并行，模拟解锁按钮连点）。无临界区保护时三者会各自读到
        // failedAttempts=0 再各自写回 1，计数丢失 → BR-9 被削弱。
        coroutineScope {
            List(3) { async(Dispatchers.Default) { unlock("wrong".toCharArray()) } }.awaitAll()
        }

        assertEquals(3, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `kdf runs on the injected dispatcher rather than the caller thread`() = runTest {
        initialize()
        val recorder = RecordingDispatcher(Dispatchers.Default)
        val unlock = UnlockVault(keyManager, headerStore, throttle(), recorder)

        unlock("Passw0rd!主密码".toCharArray()).getOrThrow()

        // M2 遗留 D-1：KDF 若在调用方（ViewModel 的 Main）执行会掉帧/ANR
        assertTrue("KDF 未被调度到注入的 dispatcher", recorder.used)
    }

    /** 记录是否真的被用于调度（而非就地执行在调用线程）。 */
    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var used = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            used = true
            delegate.dispatch(context, block)
        }
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
