package com.zz.passwordmanager.data.biometric

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.data.vault.InitializeRequest
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricGate
import com.zz.passwordmanager.domain.biometric.BiometricKeyInvalidatedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * V1-4 生物识别快捷解锁（FR-2 后半）验收：
 * 资格判定（含限流不得绕过）、成功建会话、密钥失效自愈。
 */
class UnlockWithBiometricTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val headerStore get() = VaultHeaderStore(tmp.root, macKey)
    private var now = 0L
    private val throttle get() = AuthThrottle({ now })
    private val gate = mockk<BiometricGate>()
    private val dekStore: BiometricDekStore = mockk(relaxed = true)
    private val authenticatedCipher = mockk<Cipher>(relaxed = true)
    private val useCase get() = UnlockWithBiometric(headerStore, throttle, dekStore, gate, UnconfinedTestDispatcher())
    private val toggle get() = BiometricToggle(keyManager, headerStore, throttle, gate, dekStore)

    private val password = "Passw0rd!主密码".toCharArray()

    private suspend fun initializeAndEnable() {
        InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
            InitializeRequest(
                masterPassword = password,
                confirmMasterPassword = password,
                securityQuestions = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3").map {
                    InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                },
            ),
        ).getOrThrow()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE
        every { dekStore.wrap(any(), any()) } returns WRAPPED
        val setup = toggle.prepareEnable(password).getOrThrow()
        toggle.completeEnable(setup, authenticatedCipher).getOrThrow()
    }

    @Test
    fun `prepare rejects when biometric is not enabled`() = runTest {
        InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
            InitializeRequest(
                masterPassword = password,
                confirmMasterPassword = password,
                securityQuestions = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3").map {
                    InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                },
            ),
        ).getOrThrow()

        val error = (useCase.prepare().exceptionOrNull() as AuthException).error

        assertEquals(AuthError.BiometricNotEnabled, error)
    }

    @Test
    fun `prepare returns the wrapped challenge when enabled`() = runTest {
        initializeAndEnable()

        val challenge = useCase.prepare().getOrThrow()

        assertTrue(challenge.wrappedDek.contentEquals(WRAPPED))
    }

    @Test
    fun `prepare honours the auth throttle so biometric cannot bypass lockout`() = runTest {
        initializeAndEnable()
        headerStore.write(headerStore.read()!!.copy(lockUntilEpochMs = now + 60_000L))

        val error = (useCase.prepare().exceptionOrNull() as AuthException).error

        // 生物识别不得绕过 BR-9 限流
        assertTrue(error is AuthError.LockedUntil)
    }

    @Test
    fun `complete builds a session from the authenticated cipher`() = runTest {
        initializeAndEnable()
        val dek = ByteArray(32) { it.toByte() }
        every { dekStore.unwrap(any(), any()) } returns dek
        val challenge = useCase.prepare().getOrThrow()

        val session = useCase.invoke(challenge, authenticatedCipher).getOrThrow()

        assertTrue(session.dek.contentEquals(dek))
    }

    @Test
    fun `key invalidation self heals by disabling the switch`() = runTest {
        initializeAndEnable()
        every { dekStore.unwrap(any(), any()) } throws BiometricKeyInvalidatedException("new fingerprint")
        val challenge = useCase.prepare().getOrThrow()

        val error = (useCase.invoke(challenge, authenticatedCipher).exceptionOrNull() as AuthException).error

        assertEquals(AuthError.BiometricKeyInvalidated, error)
        val header = headerStore.read()!!
        assertFalse("自愈后应关闭开关", header.biometricEnabled)
        assertTrue("自愈后应清掉包裹密文", header.wrappedDekBiometric == null)
        verify { dekStore.deleteKey() }
    }

    @Test
    fun `biometric failure does not count against the master password attempts`() = runTest {
        initializeAndEnable()
        every { dekStore.unwrap(any(), any()) } throws BiometricKeyInvalidatedException("boom")
        val challenge = useCase.prepare().getOrThrow()

        useCase.invoke(challenge, authenticatedCipher)

        // 指纹失败不是对主密码的猜测，不应消耗 BR-9 的失败计数
        assertEquals(0, headerStore.read()!!.failedAttempts)
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
        val WRAPPED = byteArrayOf(1, 2, 3, 4, 5)
    }
}
