package com.zz.passwordmanager.data.security

import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.entries.EntrySummary
import com.zz.passwordmanager.domain.entries.EntryType
import com.zz.passwordmanager.domain.security.SecurityReport
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T42/FR-13：本地安全扫描。重点是「弱/重复/长期未更新」三类判定，
 * 以及**结果里不含任何密码明文**。
 */
class DefaultSecurityScannerTest {

    private val repository: EntryRepository = mockk()

    private fun entry(
        id: String,
        password: String,
        updatedAt: Long,
    ) {
        coEvery { repository.get(id) } returns EntryDetail(
            id = id, title = id, username = "u$id", url = null, notes = null,
            categoryId = null, totpEnabled = false,
            createdAt = updatedAt, updatedAt = updatedAt,
        )
        coEvery { repository.revealPassword(id) } returns Result.success(password.toCharArray())
    }

    private fun ids(vararg ids: String) = ids.map {
        EntrySummary(id = it, title = it, username = "u", url = null, categoryId = null)
    }

    @Test
    fun `empty vault reports zeroes`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(emptyList())

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        assertEquals(0, report.totalCount)
        assertEquals(0, report.actionableCount)
    }

    @Test
    fun `weak passwords are flagged`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(ids("a", "b"))
        entry("a", "abc", NOW)          // < 8 位 → 弱
        entry("b", "Abcdefghi123456!", NOW) // 强

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        assertEquals(listOf("a"), report.weakEntryIds)
    }

    @Test
    fun `duplicate passwords are grouped`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(ids("a", "b", "c"))
        val shared = "SharedPass123!"
        entry("a", shared, NOW)
        entry("b", shared, NOW)
        entry("c", "AnotherOne456!", NOW)

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        assertEquals(1, report.duplicateGroups.size)
        assertEquals(setOf("a", "b"), report.duplicateGroups.first().toSet())
    }

    @Test
    fun `entries older than threshold are flagged as stale`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(ids("old", "new"))
        entry("old", "Abcdefghi123456!", NOW - 200 * SecurityReport.MILLIS_PER_DAY)
        entry("new", "Abcdefghi123456!", NOW)

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        assertEquals(listOf("old"), report.staleEntryIds)
    }

    @Test
    fun `exactly at threshold is not stale`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(ids("edge"))
        entry("edge", "Abcdefghi123456!", NOW - SecurityReport.STALE_DAYS_DAYS * SecurityReport.MILLIS_PER_DAY)

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        assertTrue("恰好 180 天不计入长期未更新", report.staleEntryIds.isEmpty())
    }

    @Test
    fun `report carries no plaintext`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(ids("a"))
        entry("a", "TopSecretPassword123", NOW)

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        // 结果模型的 toString 里不得出现明文
        val rendered = report.toString()
        assertTrue("结果不得含明文", !rendered.contains("TopSecret"))
    }

    @Test
    fun `failure to reveal a password does not abort the scan`() = runTest {
        coEvery { repository.search("", null) } returns flowOf(ids("a", "b"))
        coEvery { repository.get("a") } returns EntryDetail(
            id = "a", title = "a", username = "u", url = null, notes = null,
            categoryId = null, totpEnabled = false,
            createdAt = NOW, updatedAt = NOW,
        )
        coEvery { repository.revealPassword("a") } returns Result.failure(
            com.zz.passwordmanager.domain.entries.EntryRepositoryException(
                com.zz.passwordmanager.domain.entries.EntryRepositoryException.Reason.NOT_FOUND,
            ),
        )
        entry("b", "Abcdefghi123456!", NOW)

        val report = DefaultSecurityScanner(repository, now = { NOW }).scan()

        assertEquals(2, report.totalCount)
        assertTrue("解密失败的条目不计为弱密码", report.weakEntryIds.isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
