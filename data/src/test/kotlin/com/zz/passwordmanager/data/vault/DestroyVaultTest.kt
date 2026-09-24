package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import io.mockk.mockk
import io.mockk.verify
import java.security.SecureRandom
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * G-B「清除重建」验收：破坏性路径必须真的删干净，且幂等。
 * 数据库删除经 mock 验证（真实 SQLCipher 文件删除需仪器测试，此处不重复覆盖）。
 */
class DestroyVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val macKey = ByteArray(32) { it.toByte() }
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val headerStore get() = VaultHeaderStore(tmp.root, macKey)
    private val dbProvider: VaultDatabaseProvider = mockk(relaxed = true)
    // 用例须延迟构造：field 初始化时 TemporaryFolder 尚未创建 root
    private val destroyVault get() = DestroyVault(headerStore, dbProvider, UnconfinedTestDispatcher())

    @Before
    fun initialize() = runTest {
        InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
            InitializeRequest(
                masterPassword = "Passw0rd!主密码".toCharArray(),
                confirmMasterPassword = "Passw0rd!主密码".toCharArray(),
                securityQuestions = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3").map {
                    InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                },
            ),
        ).getOrThrow()
    }

    @Test
    fun `destroy removes header and database`() = runTest {
        assertEquals(true, headerStore.read() != null)

        assertEquals(true, destroyVault().isSuccess)

        // Header 消失 → VaultStatus 转「未初始化」，用户可重新建库
        assertNull(headerStore.read())
        verify(exactly = 1) { dbProvider.delete() }
    }

    @Test
    fun `destroy is idempotent`() = runTest {
        destroyVault().getOrThrow()

        // 重复点击不应报错
        assertEquals(true, destroyVault().isSuccess)
        assertNull(headerStore.read())
    }

    @Test
    fun `destroy clears the header so a fresh vault can be initialized`() = runTest {
        destroyVault().getOrThrow()

        val result = InitializeVault(keyManager, headerStore, SecureRandom(), SMALL)(
            InitializeRequest(
                masterPassword = "N3wPass0rd!新".toCharArray(),
                confirmMasterPassword = "N3wPass0rd!新".toCharArray(),
                securityQuestions = listOf("q1" to "b1", "q2" to "b2", "q3" to "b3").map {
                    InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                },
            ),
        )

        assertEquals(true, result.isSuccess)
        assertEquals(false, VaultStatus(headerStore).isInitialized().not())
    }

    private companion object {
        val SMALL = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    }
}
