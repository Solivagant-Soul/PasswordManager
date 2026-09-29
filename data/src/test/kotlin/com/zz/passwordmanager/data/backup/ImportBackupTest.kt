package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.domain.backup.MalformedBackupException
import com.zz.passwordmanager.domain.backup.UnsupportedBackupVersionException
import com.zz.passwordmanager.domain.entries.Category
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ImportBackup] 验收——**全有或全无**是本功能的核心承诺，这里逐条证明。
 *
 * 断言分两层，两层都要有：
 * 1. `replaceAllCalls == 0`：预检阶段就退回，密码库一次都没被碰；
 * 2. `snapshot()` 逐行相同：库里原有的东西**逐条**还在（不只是「条数没变」）。
 *
 * 只断言第 1 层是不够的——它只证明「没调替换」，不证明「数据没变」；
 * 只断言第 2 层也不够——数据没变可能是因为恰好没被写。两层一起才锁得住语义。
 */
class ImportBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun harness(salt: ByteArray = ByteArray(16) { 7 }) = BackupTestHarness(tmp.newFolder(), saltKdf = salt)

    /** 库里原有的 3 条，导入失败时它们必须一条不少、一字不差。 */
    private fun existingVault() = FakeBackupVault(
        listOf(
            record("old-1", "原有 A", "alice", "Old-A!"),
            record("old-2", "原有 B", "bob", "Old-B!"),
            record("old-3", "原有 C", "carol", "Old-C!"),
        ),
        listOf(Category("old-cat", "旧分类")),
    )

    private suspend fun backupFile(h: BackupTestHarness, records: List<com.zz.passwordmanager.domain.backup.BackupRecord>, categories: List<Category>): ByteArray =
        h.sealRaw(
            h.codec.id,
            h.codec.encode(com.zz.passwordmanager.domain.backup.BackupPayload(records, categories)),
        )

    private val goodBackup get() = listOf(
        record("new-1", "备份 A", "dave", "New-A!"),
        record("new-2", "备份 B", "erin", "New-B!"),
    )

    // ---- 正向 ----

    @Test
    fun `apply replaces the vault wholesale`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val frame = backupFile(h, goodBackup, listOf(Category("new-cat", "新分类")))

        val count = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray()).getOrThrow()

        assertEquals(2, count)
        assertEquals(1, vault.replaceAllCalls)
        assertEquals(listOf("new-1|备份 A|dave|New-A!", "new-2|备份 B|erin|New-B!"), vault.snapshot())
    }

    @Test
    fun `importing an empty backup empties the vault - that is what replace means`() = runBlocking {
        // 用户决策：导入 = 整库替换。空备份就是空库，UI 必须在确认框里说清条数。
        val h = harness()
        val vault = existingVault()
        val frame = backupFile(h, emptyList(), emptyList())

        val count = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray()).getOrThrow()

        assertEquals(0, count)
        assertEquals(emptyList<String>(), vault.snapshot())
    }

    @Test
    fun `inspect reports counts without touching the vault`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val frame = backupFile(h, goodBackup, listOf(Category("new-cat", "新分类")))

        val preview = h.importer(vault).inspect(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray()).getOrThrow()

        assertEquals(2, preview.entryCount)
        assertEquals(1, preview.categoryCount)
        assertEquals(0, vault.replaceAllCalls)
        assertEquals(3, vault.snapshot().size)
    }

    // ---- 失败路径：每一条都必须「库原封不动」 ----

    @Test
    fun `wrong master password leaves the vault untouched`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, goodBackup, emptyList())

        val result = h.importer(vault).apply(frame, "wrong-password".toCharArray())

        assertTrue(result.exceptionOrNull() is BackupKeyException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `truncated file leaves the vault untouched`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, goodBackup, emptyList())

        val result = h.importer(vault).apply(frame.copyOf(frame.size - 7), BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is BackupFormatException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `not a backup file at all leaves the vault untouched`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()

        val result = h.importer(vault).apply("这是一段普通文本".toByteArray(), BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is BackupFormatException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `garbage inside a correctly sealed container leaves the vault untouched`() = runBlocking {
        // 帧与认证都对，只是内层内容不是本格式——模拟「格式被换掉 / 内容被手工改坏」
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = h.sealRaw(h.codec.id, "«这不是 JSON»".toByteArray())

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is MalformedBackupException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `unsupported content format version leaves the vault untouched`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val document = h.codec.encode(com.zz.passwordmanager.domain.backup.BackupPayload(goodBackup, emptyList()))
            .decodeToString().replace("\"version\":1", "\"version\":99").encodeToByteArray()

        val result = h.importer(vault).apply(h.sealRaw(h.codec.id, document), BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is UnsupportedBackupVersionException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `unknown container version leaves the vault untouched`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, goodBackup, emptyList()).also { it[4] = 42 }

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is BackupFormatException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `unknown content format id is refused rather than guessed`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        // 一个合法的容器，帧里写着一个本 App 没有注册过的格式 id
        val frame = h.sealRaw("pmw-kdbx-from-the-future", "irrelevant".toByteArray())

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is BackupFormatException)
        assertUnchanged(vault, before)
    }

    /**
     * 核心用例：**中途失败**——5 条备份里第 3 条密码为空。
     *
     * 这是「逐条校验」最容易漏的形态：前两条已经通过，如果实现是一条条
     * 直接写库（而非先全量校验），库就会变成「2 条新的 + 3 条旧的」——
     * 既不是旧数据，也不是备份内容，正是 S45/S2 要杜绝的那种状态。
     */
    @Test
    fun `an invalid entry in the middle aborts the whole import`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val broken = listOf(
            record("new-1", "备份 A", "dave", "New-A!"),
            record("new-2", "备份 B", "erin", "New-B!"),
            record("new-3", "备份 C", "frank", ""), // ← 密码为空，违反 EntryValidation
            record("new-4", "备份 D", "grace", "New-D!"),
            record("new-5", "备份 E", "heidi", "New-E!"),
        )
        val frame = backupFile(h, broken, emptyList())

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        val e = result.exceptionOrNull()
        assertTrue("expected InvalidBackupException, got $e", e is InvalidBackupException)
        assertTrue((e as InvalidBackupException).issues.any { it.field == "password" })
        assertUnchanged(vault, before)
    }

    @Test
    fun `an entry failing the repository's own url rule aborts the whole import`() = runBlocking {
        // 证明导入复用的是 EntryValidation 这**一份**规则，而不是自己另写一套
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, listOf(record("new-1", url = "ftp://not-allowed")), emptyList())

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is InvalidBackupException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `blank username aborts the whole import`() = runBlocking {
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, listOf(record("new-1", username = "   ")), emptyList())

        assertTrue(h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())
            .exceptionOrNull() is InvalidBackupException)
        assertUnchanged(vault, before)
    }

    @Test
    fun `a backup larger than the entry limit is refused`() = runBlocking {
        // 5000 上限（Q-07）对导入同样成立，否则一份畸形备份能把库撑爆
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, (1..4).map { record("new-$it") }, emptyList())

        val result = h.importer(vault, maxEntries = 2).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        val e = result.exceptionOrNull()
        assertTrue("expected InvalidBackupException, got $e", e is InvalidBackupException)
        assertTrue((e as InvalidBackupException).issues.any { it.field == "count" })
        assertUnchanged(vault, before)
    }

    @Test
    fun `dangling category reference aborts the whole import`() = runBlocking {
        // entries.categoryId 没有外键，孤儿引用不会被 DB 拦下——必须由预检拦
        val h = harness()
        val vault = existingVault()
        val before = vault.snapshot()
        val frame = backupFile(h, listOf(record("new-1", categoryId = "no-such-category")), emptyList())

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        val e = result.exceptionOrNull()
        assertTrue("expected InvalidBackupException, got $e", e is InvalidBackupException)
        assertTrue((e as InvalidBackupException).issues.any { it.field == "categoryId" })
        assertUnchanged(vault, before)
    }

    @Test
    fun `duplicate entry ids are collapsed by the primary key without failing`() = runBlocking {
        // 不算失败：整库替换后 id 冲突由 REPLACE 语义收敛，用户拿到的就是最后一条
        val h = harness()
        val vault = existingVault()
        val frame = backupFile(
            h,
            listOf(record("dup", "先", "a", "p1"), record("dup", "后", "b", "p2")),
            emptyList(),
        )

        assertTrue(h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray()).isSuccess)
    }

    @Test
    fun `a database failure during commit is reported as failure`() = runBlocking {
        val h = harness()
        val vault = existingVault().apply { failReplaceWith = BackupIoException("disk full") }
        val frame = backupFile(h, goodBackup, emptyList())

        val result = h.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.isFailure)
        assertEquals(1, vault.replaceAllCalls) // 预检过了，确实进到了提交阶段
    }

    // ---- 主密码改过之后旧备份读不出来（必须在 UI 上讲清楚的那个后果） ----

    @Test
    fun `a backup made before the master password changed can no longer be read`() = runBlocking {
        // 换盐 = 改主密码（P-07）或密保找回重置（FR-12）之后的状态。
        // 备份密钥里含 saltKdf，因此旧备份的密钥再也算不出来——这是设计如此，不是缺陷。
        val vault = existingVault()
        val before = vault.snapshot()
        val oldDir = tmp.newFolder()
        val old = BackupTestHarness(oldDir, saltKdf = ByteArray(16) { 7 })
        val frame = backupFile(old, goodBackup, emptyList())

        val after = BackupTestHarness(tmp.newFolder(), saltKdf = ByteArray(16) { 99 })
        val result = after.importer(vault).apply(frame, BackupTestHarness.MASTER_PASSWORD.toCharArray())

        assertTrue(result.exceptionOrNull() is BackupKeyException)
        assertUnchanged(vault, before)
    }

    private fun assertUnchanged(vault: FakeBackupVault, before: List<String>) {
        assertEquals("import must not have touched the vault at all", 0, vault.replaceAllCalls)
        assertEquals("existing data must survive verbatim", before, vault.snapshot())
    }
}
