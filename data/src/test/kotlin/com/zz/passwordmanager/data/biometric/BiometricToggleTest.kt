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
import com.zz.passwordmanager.domain.biometric.BiometricGate
import io.mockk.every
import io.mockk.mockk
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    private fun toggle() = BiometricToggle(keyManager, headerStore, throttle, gate)

    private fun initialize() {
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
    fun `enable succeeds when hardware available`() {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE

        assertTrue(toggle()(enable = true, masterPassword = password).isSuccess)
        assertTrue(headerStore.read()!!.biometricEnabled)
    }

    @Test
    fun `enable fails when not enrolled`() {
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
    fun `enable fails when no hardware`() {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.NO_HARDWARE

        val result = toggle()(enable = true, masterPassword = password)

        assertTrue((result.exceptionOrNull() as AuthException).error is AuthError.BiometricUnavailable)
    }

    @Test
    fun `disable does not require hardware`() {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.NO_HARDWARE

        assertTrue(toggle()(enable = false, masterPassword = password).isSuccess)
        assertTrue(headerStore.read()!!.biometricEnabled.not())
    }

    @Test
    fun `wrong master password is rejected`() {
        initialize()
        every { gate.availability() } returns BiometricGate.Availability.AVAILABLE

        val result = toggle()(enable = true, masterPassword = "wrong".toCharArray())

        assertTrue((result.exceptionOrNull() as AuthException).error is AuthError.WrongPassword)
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
