package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.domain.backup.BackupCodec
import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.backup.BackupRecord
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupContainer] 验收：帧解析、认证、截断/篡改检测，以及**格式无关性**。
 *
 * 关键的方法论说明：最后那个 seam 用例不是「描述」容器与格式无关，而是**证明**——
 * 它让一个与 JSON 毫无关系的假 codec 走完同一个容器实例的 seal/open 全程。
 * 只要有人往容器里塞进任何格式知识（字段名、JSON 符号、按内容长度推断格式……），
 * 这个用例就红。
 */
class BackupContainerTest {

    private val keyManager = Argon2KeyManager()
    private val container = BackupContainer(keyManager)
    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `seal then open round trips bytes and codec id`() {
        val plaintext = "内容内容".encodeToByteArray()

        val frame = container.seal("pmw-json", plaintext, key)
        val opened = container.open(frame, key)

        assertEquals("pmw-json", opened.codecId)
        assertArrayEquals(plaintext, opened.plaintext)
    }

    @Test
    fun `frame starts with magic so a foreign file is rejected immediately`() {
        val frame = container.seal("pmw-json", "x".encodeToByteArray(), key)
        assertEquals("PMBK", String(frame.copyOfRange(0, 4), Charsets.US_ASCII))

        assertThrows(BackupFormatException::class.java) {
            container.open("not a backup at all, just some text".encodeToByteArray(), key)
        }
    }

    @Test
    fun `wrong key fails authentication`() {
        val frame = container.seal("pmw-json", "x".encodeToByteArray(), key)

        // 「主密码错」与「密文被改」在 AEAD 下同形，容器不区分，也不该区分
        assertThrows(BackupKeyException::class.java) { container.open(frame, otherKey) }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val frame = container.seal("pmw-json", "秘密".encodeToByteArray(), key)
        // 改密文区最后一个字节（tag 内）
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()

        assertThrows(BackupKeyException::class.java) { container.open(frame, key) }
    }

    @Test
    fun `truncated file is rejected as a format problem, not as a wrong password`() {
        val frame = container.seal("pmw-json", "一段比较长的内容".encodeToByteArray(), key)

        val truncated = frame.copyOf(frame.size - 5)

        // 关键断言：长度域不匹配必须在**进 GCM 之前**判掉。
        // 若退化成「让 GCM 去报错」，用户会看到「口令错误」并去反复输密码——
        // 而真实原因是文件没下完（S47）。
        val e = assertThrows(BackupFormatException::class.java) { container.open(truncated, key) }
        assertTrue(e.message!!.contains("length mismatch"))
    }

    @Test
    fun `trailing garbage is rejected`() {
        val frame = container.seal("pmw-json", "内容".encodeToByteArray(), key)

        assertThrows(BackupFormatException::class.java) {
            container.open(frame + byteArrayOf(0, 1, 2), key)
        }
    }

    @Test
    fun `unknown container version is rejected`() {
        val frame = container.seal("pmw-json", "内容".encodeToByteArray(), key)
        frame[4] = 99

        val e = assertThrows(BackupFormatException::class.java) { container.open(frame, key) }
        assertTrue(e.message!!.contains("version"))
    }

    @Test
    fun `empty plaintext is a valid frame`() {
        val frame = container.seal("pmw-json", ByteArray(0), key)
        assertEquals(0, container.open(frame, key).plaintext.size)
    }

    // ---- 格式无关性（技术方案 §9.2 的接缝） ----

    @Test
    fun `a second codec round trips through the very same container`() {
        // 一个刻意与 JSON 毫无关系的格式：字段用制表符分隔，值用 base64。
        // 二期加 CSV codec 时大致就是这个形状，届时容器不该有任何改动。
        val csvish = TabSeparatedCodec()
        val registry = BackupCodecRegistry(listOf(JsonBackupCodec(), csvish))
        // 同一个容器实例先后服务两种格式
        val payload = samplePayload()

        val csvFrame = container.seal(csvish.id, csvish.encode(payload), key)
        val jsonFrame = container.seal(JsonBackupCodec().id, JsonBackupCodec().encode(payload), key)

        val openedCsv = container.open(csvFrame, key)
        val openedJson = container.open(jsonFrame, key)

        // 帧里带的 id 决定用谁解；容器对两者一视同仁
        assertEquals(csvish.id, openedCsv.codecId)
        assertEquals("pmw-json", openedJson.codecId)
        assertEquals(2, registry[openedCsv.codecId]!!.decode(openedCsv.plaintext).entryCount)
        assertEquals(2, registry[openedJson.codecId]!!.decode(openedJson.plaintext).entryCount)
    }

    @Test
    fun `registry refuses to resolve an unknown codec id`() {
        val registry = BackupCodecRegistry(listOf(JsonBackupCodec()))
        assertEquals(null, registry["pmw-csv-v9"])
    }

    private fun samplePayload() = BackupPayload(
        records = listOf(
            record("e1", "GitHub", "alice", "Str0ng!pass"),
            record("e2", "银行", "6222", "0000"),
        ),
        categories = listOf(Category("c1", "工作")),
    )

    /** 测试用的第二种格式，刻意与 JSON 无关。 */
    private class TabSeparatedCodec : BackupCodec {
        override val id = "pmw-tabsep-test"

        override fun encode(payload: BackupPayload): ByteArray =
            payload.records.joinToString("\n") {
                listOf(it.id, it.title, it.username, it.password.concatToString()).joinToString("\t")
            }.encodeToByteArray()

        override fun decode(bytes: ByteArray): BackupPayload = BackupPayload(
            records = bytes.decodeToString().lines().filter { it.isNotEmpty() }.map { line ->
                val parts = line.split("\t")
                record(parts[0], parts[1], parts[2], parts[3])
            },
            categories = emptyList(),
        )
    }
}
