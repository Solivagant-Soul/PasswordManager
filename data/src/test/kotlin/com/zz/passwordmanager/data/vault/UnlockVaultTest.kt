package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T07 验收：正确解锁/错误计数/限流锁定（feature 02 映射；纯 JVM，fake clock 精确推进）。 */
class UnlockVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val headerStore get() = VaultHeaderStore(tmp.root, macKey)

    private fun throttle() = AuthThrottle({ now })

    private fun initialize() {
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
    fun `correct password unlocks with session carrying dek`() {
        initialize()

        val result = UnlockVault(keyManager, headerStore, throttle())("Passw0rd!主密码".toCharArray())

        val session = result.getOrThrow()
        assertEquals(32, session.dek.size)
        assertEquals(0, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `wrong password is rejected and counted`() {
        initialize()

        val result = UnlockVault(keyManager, headerStore, throttle())("wrong-pass".toCharArray())

        val error = (result.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.WrongPassword(attemptsRemaining = 4), error)
        assertEquals(1, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `fifth failure locks for one minute`() {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())

        repeat(4) { unlock("wrong".toCharArray()) }
        val fifth = unlock("wrong".toCharArray())

        val error = (fifth.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.LockedUntil(60_000L), error)
        assertEquals(now + 60_000L, headerStore.read()!!.lockUntilEpochMs)
    }

    @Test
    fun `locked vault rejects even correct password with remaining time`() {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())
        repeat(5) { unlock("wrong".toCharArray()) }
        now += 30_000L // 半分钟后再试

        val result = unlock("Passw0rd!主密码".toCharArray())

        val error = (result.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.LockedUntil(30_000L), error)
    }

    @Test
    fun `lock expires after one minute`() {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())
        repeat(5) { unlock("wrong".toCharArray()) }
        now += 60_000L // 恰好 1 分钟后

        val result = unlock("Passw0rd!主密码".toCharArray())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `uninitialized vault is rejected`() {
        val result = UnlockVault(keyManager, headerStore, throttle())("anything".toCharArray())

        assertEquals(AuthError.VaultMissing, (result.exceptionOrNull() as AuthException).error)
    }

    @Test
    fun `successful unlock resets failure counter`() {
        initialize()
        val unlock = UnlockVault(keyManager, headerStore, throttle())
        repeat(3) { unlock("wrong".toCharArray()) }
        assertTrue(unlock("Passw0rd!主密码".toCharArray()).isSuccess)

        assertEquals(0, headerStore.read()!!.failedAttempts)
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
