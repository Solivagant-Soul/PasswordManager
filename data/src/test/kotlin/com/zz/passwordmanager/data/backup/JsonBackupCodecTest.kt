package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.domain.backup.MalformedBackupException
import com.zz.passwordmanager.domain.backup.UnsupportedBackupVersionException
import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** [JsonBackupCodec] 验收（D5）：往返、版本策略、以及「明文不落在产物里」。 */
class JsonBackupCodecTest {

    private val codec = JsonBackupCodec()

    @Test
    fun `round trip preserves every field`() {
        val payload = BackupPayload(
            records = listOf(
                record(
                    id = "e1", title = "GitHub", username = "alice",
                    password = "Str0ng!中文🔐", url = "https://github.com", notes = "备注\n多行",
                    categoryId = "c1", totpSecret = "JBSWY3DPEHPK3PXP", favorite = true,
                    type = EntryType.WIFI, createdAt = 100L, updatedAt = 200L,
                ),
                record(
                    id = "e2", title = "银行", username = "6222 0000", password = "0000",
                    url = null, notes = null, categoryId = null, totpSecret = null,
                    favorite = false, type = EntryType.BANK_CARD, createdAt = 1L, updatedAt = 2L,
                ),
            ),
            categories = listOf(Category("c1", "工作"), Category("c2", "个人")),
        )

        val restored = codec.decode(codec.encode(payload))

        assertEquals(2, restored.entryCount)
        assertEquals(listOf("c1", "c2"), restored.categories.map { it.id })
        val first = restored.records[0]
        assertEquals("e1", first.id)
        assertArrayEquals("Str0ng!中文🔐".toCharArray(), first.password)
        assertEquals("https://github.com", first.url)
        assertEquals("备注\n多行", first.notes)
        assertEquals("c1", first.categoryId)
        assertArrayEquals("JBSWY3DPEHPK3PXP".toCharArray(), first.totpSecret!!)
        assertEquals(true, first.favorite)
        assertEquals(EntryType.WIFI, first.type)
        assertEquals(100L, first.createdAt)
        assertEquals(200L, first.updatedAt)
        assertEquals(EntryType.BANK_CARD, restored.records[1].type)
        assertNull(restored.records[1].totpSecret)
    }

    @Test
    fun `empty vault encodes to a valid empty document`() {
        val bytes = codec.encode(BackupPayload(emptyList(), emptyList()))
        val restored = codec.decode(bytes)
        assertEquals(0, restored.entryCount)
        assertEquals(0, restored.categories.size)
    }

    /**
     * 产物里不得出现口令原文。
     *
     * 这是「无明文残留」在**编码**这一侧的可断言形式：即便有人把容器密钥去掉、
     * 直接读这个 JSON，看到的也只会是 Base64。改动 [SecretCharsSerializer] 让它退回
     * 明文字符串，本用例会立刻红。
     */
    @Test
    fun `encoded bytes never contain the password in the clear`() {
        val secret = "SuperSecret123!"
        val payload = BackupPayload(
            records = listOf(record("e1", "站点", "user", secret)),
            categories = emptyList(),
        )

        val text = codec.encode(payload).decodeToString()

        assertFalse("password must not appear verbatim in the encoded document", text.contains(secret))
        assertFalse("totp secret must not appear verbatim", text.contains("JBSWY3DPEHPK3PXP"))
        // 但确实编进去了：往返能取回（见上一条）
        assertArrayEquals(secret.toCharArray(), codec.decode(codec.encode(payload)).records[0].password)
    }

    @Test
    fun `unknown format version is rejected explicitly`() {
        val bytes = codec.encode(BackupPayload(emptyList(), emptyList()))
            .decodeToString()
            .replace("\"version\":1", "\"version\":2")
            .encodeToByteArray()

        val e = assertThrows(UnsupportedBackupVersionException::class.java) { codec.decode(bytes) }
        assertEquals(2, e.formatVersion)
    }

    @Test
    fun `unknown format tag is rejected`() {
        val bytes = codec.encode(BackupPayload(emptyList(), emptyList()))
            .decodeToString()
            .replace("pmw-backup", "somebody-elses-format")
            .encodeToByteArray()

        assertThrows(MalformedBackupException::class.java) { codec.decode(bytes) }
    }

    @Test
    fun `garbage bytes are rejected as malformed not as a crash`() {
        assertThrows(MalformedBackupException::class.java) {
            codec.decode("«» 这不是 JSON «»".toByteArray())
        }
    }

    @Test
    fun `truncated json is rejected`() {
        val bytes = codec.encode(BackupPayload(emptyList(), emptyList()))
        assertThrows(MalformedBackupException::class.java) {
            codec.decode(bytes.copyOf(bytes.size / 2))
        }
    }

    @Test
    fun `secret field that is not base64 is rejected`() {
        val bytes = codec.encode(BackupPayload(emptyList(), emptyList()))
            .decodeToString()
            .replace("\"entries\":[]", "\"entries\":[{\"id\":\"e\",\"title\":\"t\",\"username\":\"u\",\"password\":\"not base64!!\",\"createdAt\":0,\"updatedAt\":0}]")
            .encodeToByteArray()

        assertThrows(MalformedBackupException::class.java) { codec.decode(bytes) }
    }

    @Test
    fun `unknown enum name falls back to ACCOUNT rather than failing the whole import`() {
        val bytes = codec.encode(BackupPayload(emptyList(), emptyList()))
            .decodeToString()
            .replace("\"entries\":[]", "\"entries\":[{\"id\":\"e\",\"title\":\"t\",\"username\":\"u\",\"password\":\"cA==\",\"type\":\"FROM_THE_FUTURE\",\"createdAt\":0,\"updatedAt\":0}]")
            .encodeToByteArray()

        val restored = codec.decode(bytes)
        // 枚举按名字存（不按序号），所以新增枚举不会让旧数据错位；
        // 读不懂的名字退化成 ACCOUNT，与 `EntryType.from` 同一策略
        assertEquals(EntryType.ACCOUNT, restored.records.single().type)
    }

    private fun record(
        id: String,
        title: String,
        username: String,
        password: String,
        url: String? = null,
        notes: String? = null,
        categoryId: String? = null,
        totpSecret: String? = null,
        favorite: Boolean = false,
        type: EntryType = EntryType.ACCOUNT,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
    ) = com.zz.passwordmanager.domain.backup.BackupRecord(
        id = id, title = title, username = username, password = password.toCharArray(),
        url = url, notes = notes, categoryId = categoryId,
        totpSecret = totpSecret?.toCharArray(), favorite = favorite, type = type,
        createdAt = createdAt, updatedAt = updatedAt,
    )
}
