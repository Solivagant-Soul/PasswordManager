package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.domain.autofill.AutofillCandidate
import com.zz.passwordmanager.domain.autofill.AutofillTarget
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动填充自持会话的安全性质单测（FR-6；技术方案 §9.2 的落地验收）。
 *
 * 这组测试是本功能的**核心门禁**，不是覆盖率装饰：
 *
 * - `锁定态拒绝任何读取` —— 证明填充服务在未认证时拿不到数据；
 * - `lock 把 DEK 字节清零` —— 证明密钥不跨调用驻留（把 [AutofillVaultSession.lock]
 *   里的 `clear()` 删掉，这条立刻红）；
 * - `超过 TTL 即失效并清零` —— 兜住「调用方忘了 lock」；
 * - `越界条目拒绝` —— 会话只覆盖本次展示过的那几条；
 * - `构造参数里没有 UI 会话` —— 证明没有把填充接回 FR-8 的自动锁定旁路。
 */
class AutofillVaultSessionTest {

    private val target = AutofillTarget(packageName = "com.github.android", domain = null)

    /** 假读端口：记下用哪把 DEK 被调用过，并可返回任意候选/明文。 */
    private class FakeReader(
        var candidates: List<AutofillCandidate> = emptyList(),
        var fill: AutofillFill? = null,
    ) : AutofillVaultReader {
        var listCalls = 0
        var lastDek: ByteArray? = null

        override suspend fun listCandidates(dek: ByteArray): List<AutofillCandidate> {
            listCalls++; lastDek = dek
            return candidates
        }

        override suspend fun readFill(dek: ByteArray, entryId: String): AutofillFill? {
            lastDek = dek
            return fill
        }
    }

    private fun session(
        unlockResult: Result<Session>,
        reader: AutofillVaultReader = FakeReader(),
        now: () -> Long = { 0L },
        ttlMs: Long = 120_000L,
    ): Pair<AutofillVaultSession, FakeReader> {
        val fake = reader as? FakeReader ?: FakeReader()
        val unlockVault = mockk<UnlockVault>()
        coEvery { unlockVault.invoke(any()) } returns unlockResult
        return AutofillVaultSession(unlockVault, fake, now, ttlMs) to fake
    }

    // ---- 性质 1：锁定态不得回答任何读取 ----

    @Test
    fun `锁定态拒绝读取候选`() = runTest {
        val (s, _) = session(Result.failure(AuthException(AuthError.WrongPassword(4))))

        val error = runCatching { s.candidates(target) }.exceptionOrNull()

        assertTrue(error is AutofillSessionException)
        assertEquals(AutofillSessionError.LOCKED, (error as AutofillSessionException).error)
        assertFalse(s.isUnlocked)
    }

    @Test
    fun `锁定态拒绝读取明文`() = runTest {
        val (s, _) = session(Result.failure(AuthException(AuthError.WrongPassword(4))))

        val error = runCatching { s.fill("any-id") }.exceptionOrNull()

        assertTrue(error is AutofillSessionException)
        assertEquals(AutofillSessionError.LOCKED, (error as AutofillSessionException).error)
    }

    @Test
    fun `认证失败后不留任何解锁态`() = runTest {
        val (s, reader) = session(Result.failure(AuthException(AuthError.WrongPassword(4))))

        s.unlock("wrong".toCharArray())

        assertFalse(s.isUnlocked)
        assertEquals(0, reader.listCalls)
    }

    // ---- 性质 2：DEK 不跨调用驻留 ----

    @Test
    fun `lock 把 DEK 字节清零`() = runTest {
        val dek = ByteArray(32) { 7 }
        val (s, _) = session(Result.success(Session(dek)))
        s.unlock("pw".toCharArray())
        assertTrue("前置条件：解锁后 DEK 应为非零", dek.any { it != 0.toByte() })

        s.lock()

        assertTrue("lock() 必须把 DEK 逐字节清零", dek.all { it == 0.toByte() })
    }

    @Test
    fun `重新解锁会先清掉上一轮残留`() = runTest {
        val first = ByteArray(32) { 1 }
        val second = ByteArray(32) { 2 }
        val unlockVault = mockk<UnlockVault>()
        coEvery { unlockVault.invoke(any()) } returnsMany listOf(Result.success(Session(first)), Result.success(Session(second)))
        val s = AutofillVaultSession(unlockVault, FakeReader(), { 0L }, 120_000L)

        s.unlock("a".toCharArray())
        s.unlock("b".toCharArray())

        assertTrue("上一轮的 DEK 必须已被清零", first.all { it == 0.toByte() })
    }

    @Test
    fun `lock 幂等`() = runTest {
        val dek = ByteArray(32) { 3 }
        val (s, _) = session(Result.success(Session(dek)))
        s.unlock("pw".toCharArray())

        s.lock(); s.lock(); s.lock()

        assertFalse(s.isUnlocked)
        assertTrue(dek.all { it == 0.toByte() })
    }

    // ---- 性质 3：TTL 兜底（调用方忘了 lock 的情形） ----

    @Test
    fun `超过 TTL 后读取即拒并当场清零`() = runTest {
        var now = 1_000L
        val dek = ByteArray(32) { 9 }
        val (s, reader) = session(Result.success(Session(dek)), FakeReader(), { now }, ttlMs = 60_000L)
        reader.candidates = listOf(AutofillCandidate("1", "GitHub", "u", null))
        s.unlock("pw".toCharArray())

        now += 60_001L
        val error = runCatching { s.candidates(target) }.exceptionOrNull()

        assertTrue(error is AutofillSessionException)
        assertFalse(s.isUnlocked)
        assertEquals(0, reader.listCalls) // 到期后连库都不该再碰
    }

    @Test
    fun `TTL 未到时仍可读`() = runTest {
        var now = 1_000L
        val (s, reader) = session(Result.success(Session(ByteArray(32) { 4 })), FakeReader(), { now }, ttlMs = 60_000L)
        reader.candidates = listOf(AutofillCandidate("1", "GitHub", "u", "https://github.com"))

        s.unlock("pw".toCharArray())
        now += 59_000L

        assertEquals(1, s.candidates(target).size)
    }

    // ---- 性质 4：作用域收窄 ----

    @Test
    fun `展示过的条目可以取出`() = runTest {
        val reader = FakeReader().apply {
            candidates = listOf(AutofillCandidate("1", "GitHub", "u@corp", "https://github.com"))
            fill = AutofillFill("u@corp", "secret".toCharArray())
        }
        val (s, _) = session(Result.success(Session(ByteArray(32) { 5 })), reader)
        s.unlock("pw".toCharArray())
        val items = s.candidates(target)

        val result = s.fill(items.single().entryId)

        assertTrue(result.isSuccess)
        assertEquals("u@corp", result.getOrThrow().username)
        assertEquals("secret", result.getOrThrow().password.concatToString())
    }

    @Test
    fun `没展示过的条目拒绝取出`() = runTest {
        val reader = FakeReader().apply {
            candidates = listOf(AutofillCandidate("1", "GitHub", "u", "https://github.com"))
            fill = AutofillFill("u", "secret".toCharArray())
        }
        val (s, _) = session(Result.success(Session(ByteArray(32) { 6 })), reader)
        s.unlock("pw".toCharArray())
        s.candidates(target)

        val result = s.fill("some-other-entry")

        assertTrue(result.isFailure)
        assertEquals(
            AutofillSessionError.OUT_OF_SCOPE,
            (result.exceptionOrNull() as AutofillSessionException).error,
        )
    }

    @Test
    fun `lock 之后作用域一并清空`() = runTest {
        val reader = FakeReader().apply {
            candidates = listOf(AutofillCandidate("1", "GitHub", "u", "https://github.com"))
        }
        val (s, _) = session(Result.success(Session(ByteArray(32) { 8 })), reader)
        s.unlock("pw".toCharArray())
        s.candidates(target)
        s.unlock("pw".toCharArray()) // 重新解锁

        val result = s.fill("1")

        assertEquals(
            AutofillSessionError.OUT_OF_SCOPE,
            (result.exceptionOrNull() as AutofillSessionException).error,
        )
    }

    @Test
    fun `无匹配时返回空列表而不是抛错`() = runTest {
        val (s, reader) = session(Result.success(Session(ByteArray(32) { 2 })))
        reader.candidates = listOf(AutofillCandidate("1", "别处", "u", "https://gitlab.com"))
        s.unlock("pw".toCharArray())

        assertTrue(s.candidates(target).isEmpty())
    }

    // ---- 性质 5：没有复用 UI 会话 ----

    @Test
    fun `构造参数里没有 UI 会话管理器`() {
        // 默认参数会让 Kotlin 再合成一个带 DefaultConstructorMarker 的构造器，
        // 所以取「非合成的那个」，否则 single() 直接抛。
        val params = AutofillVaultSession::class.java.declaredConstructors
            .first { !it.isSynthetic }
            .parameterTypes.map { it.simpleName }

        assertFalse(
            "自动填充会话不得注入 ${'$'}VaultSessionManager——那是 FR-8 的旁路",
            params.contains("VaultSessionManager"),
        )
        assertTrue(params.contains("UnlockVault"))
    }

    @Test
    fun `会话的公开面只有解锁取候选取明文与锁定`() {
        // 白名单式断言：将来谁往这个类上加一个「顺手把整个库读出来」的方法，
        // 这条会直接红。安全相关的类宁可用白名单卡死，也别等评审才发现。
        // 挂起函数在字节码里名字带 `-<hash>` 后缀，先剥掉再比。
        val public = AutofillVaultSession::class.java.methods
            .filterNot { it.isSynthetic || it.declaringClass == Any::class.java }
            .map { it.name.substringBefore('-') }
            .toSet()

        assertEquals(setOf("unlock", "candidates", "fill", "lock", "isUnlocked"), public)
    }
}
