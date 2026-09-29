package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.domain.backup.BackupCodec
import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.backup.BackupVault
import com.zz.passwordmanager.domain.entries.Category
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ExportBackup] 验收：往返、**明文清零**、空库、落盘失败。
 *
 * 清零那条用例是本文件的核心：它断言的是「导出结束后，编解码器拿到过的那批
 * CharArray 里没有一个字符还活着」。删掉 `ExportBackup` 里的 `payload?.clear()`
 * 就会红——这正是「零化一旦回退必须被抓住」的意思。
 */
class ExportBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun harness() = BackupTestHarness(tmp.newFolder())

    @Test
    fun `export writes a file the importer can read back`() = runBlocking {
        val h = harness()
        val file = File(tmp.root, "out.pmbak")
        val vault = FakeBackupVault(
            listOf(
                record("e1", "GitHub", "alice", "Str0ng!pass", url = "https://github.com"),
                record("e2", "银行", "6222", "0000", type = com.zz.passwordmanager.domain.entries.EntryType.BANK_CARD),
            ),
            listOf(Category("c1", "工作")),
        )

        val store = FileBackupFileStore(file)
        val exported = h.exporter(vault).export(
            BackupTestHarness.MASTER_PASSWORD.toCharArray(),
            store,
        )

        assertEquals(2, exported.getOrThrow())
        val restored = h.importer(vault).inspect(
            FileBackupFileStore(file).read(),
            BackupTestHarness.MASTER_PASSWORD.toCharArray(),
        ).getOrThrow()
        assertEquals(2, restored.entryCount)
        assertEquals(1, restored.categoryCount)
    }

    @Test
    fun `export clears every plaintext password it touched`() = runBlocking {
        val h = harness()
        val file = File(tmp.root, "out.pmbak")
        val vault = FakeBackupVault(listOf(record("e1", password = "hunter2"), record("e2", password = "🔐密碼")))

        // 记录 codec 拿到的正是 vault 交出的那批 CharArray 实例（不是副本），
        // 因此导出结束后它们必须已被清零
        val recorder = RecordingCodec(h.codec)
        val exporter = ExportBackup(vault, h.container, recorder, h.keyDeriver)

        val result = exporter.export(
            BackupTestHarness.MASTER_PASSWORD.toCharArray(),
            FileBackupFileStore(file),
        )

        assertTrue(result.isSuccess)
        assertEquals(2, recorder.seen.size)
        recorder.seen.forEach { chars ->
            assertArrayEquals(
                "plaintext password survived export",
                CharArray(chars.size),
                chars,
            )
        }
    }

    @Test
    fun `export clears plaintext even when the write fails`() = runBlocking {
        val h = harness()
        val vault = FakeBackupVault(listOf(record("e1", password = "hunter2")))
        val recorder = RecordingCodec(h.codec)
        val exporter = ExportBackup(vault, h.container, recorder, h.keyDeriver)

        val result = exporter.export(
            BackupTestHarness.MASTER_PASSWORD.toCharArray(),
            ExplodingFileStore,
        )

        assertTrue(result.isFailure)
        assertArrayEquals(CharArray("hunter2".length), recorder.seen.single())
    }

    @Test
    fun `empty vault still produces a valid readable backup`() = runBlocking {
        // S48：空库导出要么生成空备份文件，要么明确提示——本实现选择前者
        val h = harness()
        val file = File(tmp.root, "empty.pmbak")
        val vault = FakeBackupVault(emptyList())
        val store = FileBackupFileStore(file)

        val result = h.exporter(vault).export(
            BackupTestHarness.MASTER_PASSWORD.toCharArray(),
            store,
        )

        assertEquals(0, result.getOrThrow())
        val preview = h.importer(vault).inspect(store.read(), BackupTestHarness.MASTER_PASSWORD.toCharArray())
        assertEquals(0, preview.getOrThrow().entryCount)
    }

    @Test
    fun `export refuses to write when the vault is locked`() = runBlocking {
        val h = harness()
        val locked = object : BackupVault {
            override suspend fun readAll(): BackupPayload =
                throw com.zz.passwordmanager.data.vault.VaultLockedException()

            override suspend fun replaceAll(payload: BackupPayload) = error("not reached")
        }
        val store = FileBackupFileStore(File(tmp.root, "nope.pmbak"))

        val result = h.exporter(locked).export("x".toCharArray(), store)

        assertTrue(result.exceptionOrNull() is com.zz.passwordmanager.data.vault.VaultLockedException)
        assertEquals(false, File(tmp.root, "nope.pmbak").exists())
    }

    /** 包一层真实 codec，只为把「编解码器拿到的实例」截下来。 */
    private class RecordingCodec(private val delegate: BackupCodec) : BackupCodec {
        val seen = mutableListOf<CharArray>()
        override val id = delegate.id

        override fun encode(payload: BackupPayload): ByteArray {
            payload.records.forEach { seen.add(it.password) }
            return delegate.encode(payload)
        }

        override fun decode(bytes: ByteArray): BackupPayload = delegate.decode(bytes)
    }

    private object ExplodingFileStore : BackupFileStore {
        override suspend fun read(): ByteArray = error("not reached")
        override suspend fun write(bytes: ByteArray): Unit = throw BackupIoException("no provider")
    }
}
