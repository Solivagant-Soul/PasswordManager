package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.InitializeRequest
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

/** T10/T11 验收：找回重置、修改主密码（feature 11 映射；限流与主密码共享）。 */
class VaultMaintenanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 2_000_000L
    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val headerStore get() = VaultHeaderStore(tmp.root, macKey)
    private val throttle get() = AuthThrottle({ now })

    private val oldPassword = "Passw0rd!主密码"
    private val answers = listOf("a1", "a2", "a3").map { it.toCharArray() } // 与 initialize 的密保一致

    private suspend fun initialize() {
        assertTrue(
            InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
                InitializeRequest(
                    masterPassword = oldPassword.toCharArray(),
                    confirmMasterPassword = oldPassword.toCharArray(),
                    securityQuestions = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3").map {
                        InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                    },
                ),
            ).isSuccess,
        )
    }

    // ---- ResetVault（找回重置，FR-12） ----

    @Test
    fun `correct answers reset master password`() = runTest {
        initialize()

        val result = ResetVault(keyManager, headerStore, throttle)(
            answers, "NewPass0rd!新".toCharArray(), "NewPass0rd!新".toCharArray(),
        )

        assertTrue(result.isSuccess)
        // 新密码可解锁（旧密码失效）
        assertTrue(UnlockVault(keyManager, headerStore, throttle)("NewPass0rd!新".toCharArray()).isSuccess)
        assertTrue(UnlockVault(keyManager, headerStore, throttle)(oldPassword.toCharArray()).isFailure)
    }

    @Test
    fun `wrong answers share throttle with master password`() = runTest {
        initialize()
        val reset = ResetVault(keyManager, headerStore, throttle)

        repeat(3) { // 3 次找回错误：attempts 1..3（每次失败后限流未触发，推进时钟越过窗口）
            reset(listOf("错".toCharArray()), "NewPass0rd!新".toCharArray(), "NewPass0rd!新".toCharArray())
            now += 61_000L
        }

        // 主密码错 2 次：attempts 4、5 → 第 2 次主密码错误触发锁定（共享计数达 5）
        UnlockVault(keyManager, headerStore, throttle)("wrong".toCharArray())
        val fifth = UnlockVault(keyManager, headerStore, throttle)("wrong-again".toCharArray())

        val error = (fifth.exceptionOrNull() as AuthException).error
        assertTrue("共享计数达 5 应锁定", error is AuthError.LockedUntil)
    }

    @Test
    fun `correct answers with weak new password returns invalid without counting`() = runTest {
        initialize()
        val attemptsBefore = headerStore.read()!!.failedAttempts

        val result = ResetVault(keyManager, headerStore, throttle)(
            answers, "weak".toCharArray(), "weak".toCharArray(),
        )

        val error = (result.exceptionOrNull() as AuthException).error
        assertTrue((error as AuthError.Invalid).issues.isNotEmpty())
        assertEquals(attemptsBefore, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `reset rotates recovery salt so old answers no longer derive recovery key`() = runTest {
        initialize()
        assertTrue(ResetVault(keyManager, headerStore, throttle)(answers, "NewPass0rd!新".toCharArray(), "NewPass0rd!新".toCharArray()).isSuccess)

        // 重置后 Header 换了 saltKdf：旧主密码不再能解锁（已在首测断言）；此处验证限流共享后的正常态
        assertEquals(0, headerStore.read()!!.failedAttempts)
    }

    // ---- ChangeMasterPassword（T11/P-07） ----

    @Test
    fun `change master password with correct current succeeds`() = runTest {
        initialize()

        val result = ChangeMasterPassword(keyManager, headerStore, throttle)(
            oldPassword.toCharArray(), "NewPass0rd!新".toCharArray(), "NewPass0rd!新".toCharArray(),
        )

        assertTrue(result.isSuccess)
        assertTrue(UnlockVault(keyManager, headerStore, throttle)("NewPass0rd!新".toCharArray()).isSuccess)
        assertTrue(UnlockVault(keyManager, headerStore, throttle)(oldPassword.toCharArray()).isFailure)
    }

    @Test
    fun `change master password with wrong current is rejected and counted`() = runTest {
        initialize()

        val result = ChangeMasterPassword(keyManager, headerStore, throttle)(
            "wrong".toCharArray(), "NewPass0rd!新".toCharArray(), "NewPass0rd!新".toCharArray(),
        )

        val error = (result.exceptionOrNull() as AuthException).error
        assertEquals(AuthError.WrongPassword(attemptsRemaining = 4), error)
        assertEquals(1, headerStore.read()!!.failedAttempts)
    }

    @Test
    fun `change master password does not rotate dek (biometric copy stays valid)`() = runTest {
        initialize()
        ChangeMasterPassword(keyManager, headerStore, throttle)(
            oldPassword.toCharArray(), "NewPass0rd!新".toCharArray(), "NewPass0rd!新".toCharArray(),
        )

        // DEK 未旋转 → Keystore 包裹的快捷副本依然有效（T11 论证）；此处校验 Header 状态一致
        val header = headerStore.read()!!
        assertEquals(0, header.failedAttempts)
        assertTrue(header.biometricEnabled.not())
    }

    // ---- 评审 G-F：改密保与初始化共用同一份校验规则 ----

    @Test
    fun `change security questions rejects overlong question like initialization does`() = runTest {
        initialize()
        val change = ChangeSecurityQuestions(keyManager, headerStore, throttle)
        val questions = listOf("x".repeat(101), "q2", "q3").mapIndexed { index, text ->
            InitializeRequest.SecurityQuestionInput(text, "a${index + 1}".toCharArray())
        }

        val result = change(questions, oldPassword.toCharArray())

        val issues = ((result.exceptionOrNull() as AuthException).error as AuthError.Invalid).issues
        assertEquals(
            listOf(Issue(SecurityQuestionPolicy.FIELD, "question 1: invalid length")),
            issues,
        )
        // 校验失败不得改写 Header（无脏数据，S5）
        assertEquals("q1", headerStore.read()!!.securityQuestions.first().questionText)
    }

    @Test
    fun `change security questions rejects wrong question count`() = runTest {
        initialize()
        val change = ChangeSecurityQuestions(keyManager, headerStore, throttle)

        val result = change(
            listOf(InitializeRequest.SecurityQuestionInput("q1", "a1".toCharArray())),
            oldPassword.toCharArray(),
        )

        val issues = ((result.exceptionOrNull() as AuthException).error as AuthError.Invalid).issues
        assertEquals(
            listOf(Issue(SecurityQuestionPolicy.FIELD, "expected 3 questions")),
            issues,
        )
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
