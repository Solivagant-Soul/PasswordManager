package com.zz.passwordmanager.data.biometric

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.InitializeRequest
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricGate
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

/** T09/P-03：开关须验主密码；开启须硬件可用（MockK 首次实战）。 */
class BiometricToggleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val headerStore get() = VaultHeaderStore(tmp.root, macKey)
    private val throttle = AuthThrottle({ 0L })
    private val gate = mockk<BiometricGate>()
    private val password = "Passw0rd!主密码".toCharArray()

    private val dekStore: BiometricDekStore = mockk(relaxed = true) {
        every { wrap(any()) } answers { firstArg<ByteArray>().reversedArray() }
    }

    private fun toggle() = BiometricToggle(keyManager, headerStore, throttle, gate, dekStore)

    private suspend fun initialize() {
        assertTrue(
            InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
                InitializeRequest(
                    masterPassword = password,
                    confirmMasterPassword = password,
                    securityQuestions = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3").map {
                        InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                    },
                ),
            ).isSuccess,
        )
    }

    @Test
    fun `enable succeeds when hardware available`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE

        assertTrue(toggle()(enable = true, masterPassword = password).isSuccess)
        assertTrue(headerStore.read()!!.biometricEnabled)
    }

    @Test
    fun `enable fails when not enrolled`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.NOT_ENROLLED

        val result = toggle()(enable = true, masterPassword = password)

        val error = (result.exceptionOrNull() as AuthException).error
        assertEquals(
            AuthError.BiometricUnavailable(BiometricGate.Availability.NOT_ENROLLED),
            error,
        )
        assertTrue(headerStore.read()!!.biometricEnabled.not())
    }

    @Test
    fun `enable fails when no hardware`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.NO_HARDWARE

        val result = toggle()(enable = true, masterPassword = password)

        assertTrue((result.exceptionOrNull() as AuthException).error is AuthError.BiometricUnavailable)
    }

    @Test
    fun `disable does not require hardware`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.NO_HARDWARE

        assertTrue(toggle()(enable = false, masterPassword = password).isSuccess)
        assertTrue(headerStore.read()!!.biometricEnabled.not())
    }

    @Test
    fun `wrong master password is rejected`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE

        val result = toggle()(enable = true, masterPassword = "wrong".toCharArray())

        assertTrue((result.exceptionOrNull() as AuthException).error is AuthError.WrongPassword)
    }

    // ---- V1-4：包裹密文必须真的落库，关闭时必须清干净 ----

    @Test
    fun `enable stores the keystore-wrapped dek alongside the switch`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE

        assertTrue(toggle()(enable = true, masterPassword = password).isSuccess)

        val header = headerStore.read()!!
        assertTrue(header.biometricEnabled)
        // 此前 BiometricToggle 只翻开关、从不 wrap，「快捷解锁」在数据层根本没闭环
        assertNotNull("开启后应存有 Keystore 包裹密文", header.wrappedDekBiometric)
        assertTrue(header.wrappedDekBiometric!!.isNotEmpty())
    }

    @Test
    fun `disable clears the wrapped dek and deletes the keystore key`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE
        toggle()(enable = true, masterPassword = password)

        assertTrue(toggle()(enable = false, masterPassword = password).isSuccess)

        val header = headerStore.read()!!
        assertNull("关闭后不应残留包裹密文", header.wrappedDekBiometric)
        assertTrue(header.biometricEnabled.not())
        // 残留 Keystore 密钥是无主凭证
        verify { dekStore.deleteKey() }
    }

    @Test
    fun `failing keystore wrap surfaces as unavailable rather than half enabling`() = runTest {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE
        every { dekStore.wrap(any()) } throws IllegalStateException("keystore refused")

        val result = toggle()(enable = true, masterPassword = password)

        assertTrue((result.exceptionOrNull() as AuthException).error is AuthError.BiometricUnavailable)
        // 失败时不得留下「开关开了但没密文」的中间态
        assertTrue(headerStore.read()!!.biometricEnabled.not())
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
