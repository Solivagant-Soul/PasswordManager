package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

/**
 * T06 验收：feature 01 相关场景在用例层通过（纯 JVM，Argon2 小参数提速）。
 */
class InitializeVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager = Argon2KeyManager(recoveryKdfParams = SMALL_PARAMS)

    private fun useCase(): InitializeVault =
        InitializeVault(
            keyManager = keyManager,
            headerStore = VaultHeaderStore(directory = tmp.root, macKey = macKey),
            kdfParams = SMALL_PARAMS,
        )

    private fun request(
        password: String = "Passw0rd!主密码",
        confirm: String = password,
        questions: List<Pair<String, String>> = listOf("问题一" to "答案一", "问题二" to "答案二", "问题三" to "答案三"),
    ) = InitializeRequest(
        masterPassword = password.toCharArray(),
        confirmMasterPassword = confirm.toCharArray(),
        securityQuestions = questions.map {
            InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
        },
    )

    // ---- 成功路径 ----

    @Test
    fun `initialize succeeds and header is readable without dek`() = runTest {
        assertTrue(useCase()(request()).isSuccess)

        val header = VaultHeaderStore(tmp.root, macKey).read()
        assertNotNull(header)
        assertEquals(16, header!!.saltKdf.size)
        assertEquals(16, header.saltRecovery.size)
        assertEquals(SMALL_PARAMS, header.kdfParams)
        assertEquals(listOf(1, 2, 3), header.securityQuestions.map { it.position })
        assertEquals("问题一", header.securityQuestions[0].questionText)
    }

    @Test
    fun `master and recovery paths wrap the same dek`() = runTest {
        useCase()(request())

        val header = VaultHeaderStore(tmp.root, macKey).read()!!
        val password = "Passw0rd!主密码".toCharArray()
        val answers = listOf("答案一", "答案二", "答案三").map { it.toCharArray() }

        // D4 根基：两条独立路径解出同一 DEK
        val fromMaster = keyManager.unwrapDek(
            header.wrappedDekMaster,
            keyManager.deriveKek(password, header.saltKdf, header.kdfParams),
        )
        val fromRecovery = keyManager.unwrapDek(
            header.wrappedDekRecovery,
            keyManager.deriveRecoveryKek(answers, header.saltRecovery),
        )
        assertArrayEquals(fromMaster, fromRecovery)
        assertEquals(32, fromMaster.size)
    }

    @Test
    fun `initialization is idempotent guarded`() = runTest {
        useCase()(request())

        val second = useCase()(request())

        val error = second.exceptionOrNull() as InitializationException
        assertEquals(InitializationError.AlreadyInitialized, error.error)
    }

    // ---- 校验失败（对照 feature 01） ----

    @Test
    fun `mismatched confirmation is rejected`() = runTest {
        val result = useCase()(request(confirm = "Passw0rd!不同"))

        val error = (result.exceptionOrNull() as InitializationException).error
        assertTrue((error as InitializationError.Invalid).issues.any { it.field == "confirmMasterPassword" })
    }

    @Test
    fun `too short password is rejected`() = runTest {
        val result = useCase()(request(password = "Ab1x"))

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "masterPassword" && it.message == "TOO_SHORT" })
    }

    @Test
    fun `single class password is rejected`() = runTest {
        val result = useCase()(request(password = "abcdefgh"))

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "masterPassword" && it.message == "SINGLE_CLASS" })
    }

    @Test
    fun `blank only password is rejected`() = runTest {
        val result = useCase()(request(password = "        "))

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "masterPassword" && it.message == "BLANK_ONLY" })
    }

    @Test
    fun `control chars in password are rejected`() = runTest {
        val result = useCase()(request(password = "abc1234" + 1.toChar())) // 1.toChar() = U+0001 控制字符

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "masterPassword" && it.message == "CONTROL_CHARS" })
    }

    @Test
    fun `fewer than three security questions are rejected`() = runTest {
        val result = useCase()(
            request(questions = listOf("问题一" to "答案一", "问题二" to "答案二")),
        )

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "securityQuestions" })
    }

    @Test
    fun `overlong question is rejected`() = runTest {
        val result = useCase()(
            request(questions = listOf("长".repeat(101) to "答案一", "问题二" to "答案二", "问题三" to "答案三")),
        )

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "securityQuestions" && it.message.contains("question 1") })
    }

    @Test
    fun `blank answer is rejected`() = runTest {
        val result = useCase()(
            request(questions = listOf("问题一" to "   ", "问题二" to "答案二", "问题三" to "答案三")),
        )

        val issues = (result.exceptionOrNull() as InitializationException).error as InitializationError.Invalid
        assertTrue(issues.issues.any { it.field == "securityQuestions" && it.message.contains("answer 1") })
    }

    @Test
    fun `validation failure leaves vault uninitialized (no dirty data)`() = runTest {
        useCase()(request(password = "abc")) // S5：非法请求不得写入任何东西

        assertNull(VaultHeaderStore(tmp.root, macKey).read())
    }

    private companion object {
        val SMALL_PARAMS = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
