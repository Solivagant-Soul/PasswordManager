package com.zz.passwordmanager.data.entries

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.EntryDao
import com.zz.passwordmanager.data.db.EntryRow
import com.zz.passwordmanager.data.db.EntryTagDao
import com.zz.passwordmanager.data.db.TagDao
import com.zz.passwordmanager.data.db.VaultDatabase
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.vault.Session
import com.zz.passwordmanager.data.vault.VaultLockedException
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.entries.EntryRepositoryException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T14/T18 验收（纯 JVM）：CRUD 委派、校验、5000 上限、加密写入/明文不落盘、reveal 解密、门禁。
 * DAO mockk；KeyManager 真（小参数）；SessionManager mockk。
 */
class EntryRepositoryImplTest {

    private val dek = ByteArray(32) { it.toByte() }
    private val session = Session(dek)
    private val sessionManager: VaultSessionManager = mockk {
        every { requireUnlocked() } returns session
    }
    private val entryDao: EntryDao = mockk(relaxed = true)
    private val tagDao: TagDao = mockk(relaxed = true)
    private val entryTagDao: EntryTagDao = mockk(relaxed = true)
    private val mockDb: VaultDatabase = mockk {
        every { entryDao() } returns entryDao
        every { tagDao() } returns tagDao
        every { entryTagDao() } returns entryTagDao
    }
    private val dbProvider: VaultDatabaseProvider = mockk()
    private val keyManager: KeyManager = Argon2KeyManager(recoveryKdfParams = SMALL)
    private val repository = EntryRepositoryImpl(dbProvider, sessionManager, keyManager)

    private fun input(
        title: String = "站点",
        password: String = "P@ss 明文",
        tags: List<String> = emptyList(),
        url: String? = "https://example.com",
    ) = EntryInput(
        title = title,
        username = "user",
        password = password.toCharArray(),
        url = url,
        notes = null,
        categoryId = null,
        tags = tags,
    )

    @Before
    fun setUp() {
        coEvery { entryDao.count() } returns 0
        // 显式接收者：块内 `get(any())` 会撞 MockKMatcherScope 的解析歧义
        every { dbProvider.get(any()) } returns mockDb
    }

    // ---- create（T18：明文不落盘） ----

    @Test
    fun `create encrypts password so plaintext never lands in entity`() = runTest {
        val result = repository.create(input())

        assertTrue(result.isSuccess)
        val slot = slot<com.zz.passwordmanager.data.db.EntryEntity>()
        coVerify(exactly = 1) { entryDao.upsert(capture(slot)) }
        val entity = slot.captured
        assertTrue(
            "密文不得等于明文字节",
            !entity.passwordCipher.contentEquals("P@ss 明文".encodeToByteArray()),
        )
        // 可用会话 DEK 解回明文（T18 往返）
        assertEquals("P@ss 明文", keyManager.decrypt(entity.passwordCipher, dek).decodeToString())
    }

    @Test
    fun `create rejects when limit reached`() = runTest {
        coEvery { entryDao.count() } returns 5_000

        val result = repository.create(input())

        assertEquals(
            EntryRepositoryException.Reason.LIMIT_REACHED,
            (result.exceptionOrNull() as EntryRepositoryException).reason,
        )
    }

    @Test
    fun `create rejects empty title and empty password`() = runTest {
        val result = repository.create(input(title = "  ", password = ""))

        val issues = (result.exceptionOrNull() as EntryRepositoryException).issues
        assertTrue(issues.any { it.field == "title" })
        assertTrue(issues.any { it.field == "password" })
    }

    @Test
    fun `create rejects non http url`() = runTest {
        val result = repository.create(input(url = "ftp://example.com"))

        val issues = (result.exceptionOrNull() as EntryRepositoryException).issues
        assertTrue(issues.any { it.field == "url" })
    }

    @Test
    fun `create rejects more than 20 tags`() = runTest {
        val result = repository.create(input(tags = (1..21).map { "t$it" }))

        val issues = (result.exceptionOrNull() as EntryRepositoryException).issues
        assertTrue(issues.any { it.field == "tags" })
    }

    // ---- reveal ----

    @Test
    fun `revealPassword decrypts stored cipher`() = runTest {
        val cipher = keyManager.encrypt("明文密码".encodeToByteArray(), dek)
        coEvery { entryDao.getById("e1") } returns entityWith(cipher)

        val revealed = repository.revealPassword("e1")

        assertArrayEquals("明文密码".toCharArray(), revealed.getOrThrow())
    }

    @Test
    fun `revealPassword reports missing entry`() = runTest {
        coEvery { entryDao.getById("nope") } returns null

        val result = repository.revealPassword("nope")

        assertEquals(
            EntryRepositoryException.Reason.NOT_FOUND,
            (result.exceptionOrNull() as EntryRepositoryException).reason,
        )
    }

    // ---- search / delete ----

    @Test
    fun `search normalizes query and maps rows to summaries`() = runTest {
        coEvery {
            entryDao.search("site", null)
        } returns listOf(EntryRow("e1", "Site", "u", "https://x", null))

        val summaries = repository.search("SiTe", null, null).first()

        assertEquals(1, summaries.size)
        assertEquals("Site", summaries[0].title)
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        coEvery { entryDao.deleteById("e1") } returns Unit

        assertTrue(repository.delete("e1").isSuccess)
        coVerify(exactly = 1) { entryDao.deleteById("e1") }
    }

    // ---- 门禁（BR-7） ----

    @Test
    fun `locked session blocks create via exception`() {
        every { sessionManager.requireUnlocked() } throws VaultLockedException()

        assertThrows(VaultLockedException::class.java) {
            kotlinx.coroutines.runBlocking { repository.create(input()) }
        }
    }

    @Test
    fun `locked session blocks reveal via exception`() {
        every { sessionManager.requireUnlocked() } throws VaultLockedException()

        assertThrows(VaultLockedException::class.java) {
            kotlinx.coroutines.runBlocking { repository.revealPassword("e1") }
        }
    }

    private fun entityWith(cipher: ByteArray) = com.zz.passwordmanager.data.db.EntryEntity(
        id = "e1", title = "t", titleLower = "t", username = "u", usernameLower = "u",
        passwordCipher = cipher, url = null, urlLower = null, notes = null,
        categoryId = null, totpSecretCipher = null, createdAt = 1L, updatedAt = 1L,
    )

    private companion object {
        val SMALL = KdfParams(1, 8192, 1)
    }
}
