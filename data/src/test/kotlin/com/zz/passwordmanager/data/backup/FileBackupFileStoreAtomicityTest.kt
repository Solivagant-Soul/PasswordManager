package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FileBackupFileStore] 验收，重点是**原子性**（S2/S47）。
 *
 * 「断言文件写出来了」证明不了任何原子性——它对直接写目标文件的实现同样成立。
 * 真正有判别力的断言只有一条：**写到一半失败时，已存在的旧文件仍然完好**。
 * 磁盘不会配合测试失败，所以 [FileBackupFileStore] 把「写暂存文件」那一步做成了
 * 可注入的 `stageWriter`，由本测试注入一个写一半就抛异常的版本。
 *
 * 若有人把实现改回「直接往目标文件写」：注入点失效 → 旧文件被截断 → 用例红；
 * 若有人改回「先写 .part 再 copy 到目标」：失败后目标已残缺 → 用例红。
 */
class FileBackupFileStoreAtomicityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val container = BackupContainer(Argon2KeyManager())
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `write then read returns exactly the bytes`() = runBlocking {
        val target = File(tmp.root, "b.pmbak")
        val bytes = container.seal("pmw-json", "内容".encodeToByteArray(), key)

        FileBackupFileStore(target).write(bytes)

        assertArrayEquals(bytes, FileBackupFileStore(target).read())
    }

    @Test
    fun `write leaves no staging file behind`() = runBlocking {
        val target = File(tmp.root, "b.pmbak")

        FileBackupFileStore(target).write("x".encodeToByteArray())

        assertFalse(File(tmp.root, "b.pmbak.part").exists())
    }

    @Test
    fun `successful overwrite replaces the old content wholesale`() = runBlocking {
        val target = File(tmp.root, "b.pmbak")
        val store = FileBackupFileStore(target)
        store.write("old".encodeToByteArray())

        val newer = "a-much-longer-new-content".encodeToByteArray()
        store.write(newer)

        assertArrayEquals(newer, store.read())
    }

    @Test
    fun `write interrupted midway leaves the previous backup untouched`() {
        val target = File(tmp.root, "b.pmbak")
        val original = "原有备份内容".encodeToByteArray()
        target.writeBytes(original)

        val store = FileBackupFileStore(
            target = target,
            // 写到一半就失败——模拟存储不足 / 进程被杀
            stageWriter = { staging, bytes ->
                FileOutputStream(staging).use { it.write(bytes, 0, bytes.size / 2) }
                throw IOException("disk full")
            },
        )

        assertThrows(BackupIoException::class.java) {
            runBlocking { store.write("新内容".encodeToByteArray()) }
        }

        // 核心断言：旧文件逐字节未变
        assertArrayEquals(original, target.readBytes())
        // 半成品被清理掉
        assertFalse(File(tmp.root, "b.pmbak.part").exists())
    }

    @Test
    fun `first ever write that fails leaves no file at all`() {
        val target = File(tmp.root, "b.pmbak")
        val store = FileBackupFileStore(
            target = target,
            stageWriter = { staging, bytes ->
                FileOutputStream(staging).use { it.write(bytes, 0, 1) }
                throw IOException("interrupted")
            },
        )

        assertThrows(BackupIoException::class.java) {
            runBlocking { store.write("x".encodeToByteArray()) }
        }

        // S47：不产生「看似成功实则损坏」的备份文件
        assertFalse(target.exists())
    }

    @Test
    fun `the staging file really is used - a direct write would skip this`() = runBlocking {
        val target = File(tmp.root, "b.pmbak")
        var stagingPath: String? = null
        // 仍然要真写：注入点只替换「往哪写」，不替换「写不写」——
        // 少写一个字节会让随后的 move 抛 NoSuchFileException，测试就红在错误的原因上
        val store = FileBackupFileStore(target, stageWriter = { staging, bytes ->
            stagingPath = staging.absolutePath
            staging.writeBytes(bytes)
        })

        store.write("x".encodeToByteArray())

        // 这条断言的作用是让「注入点被绕过」立刻暴露，而不是静悄悄地让上面的
        // 失败注入失效、测试假绿
        assertEquals(File(tmp.root, "b.pmbak.part").absolutePath, stagingPath)
        assertTrue(target.exists())
    }

    @Test
    fun `read of a missing file reports it rather than returning empty`() {
        assertThrows(BackupIoException::class.java) {
            runBlocking { FileBackupFileStore(File(tmp.root, "nope.pmbak")).read() }
        }
    }
}
