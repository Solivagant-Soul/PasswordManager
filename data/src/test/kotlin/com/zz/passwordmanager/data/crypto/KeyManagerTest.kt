package com.zz.passwordmanager.data.crypto

import com.zz.passwordmanager.data.header.KdfParams
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 验收：加解密往返一致、篡改致失败、参数校验（纯 JVM，Argon2 用小参数提速）。
 */
class KeyManagerTest {

    private val keyManager: KeyManager = Argon2KeyManager(
        recoveryKdfParams = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1),
    )

    private val salt = ByteArray(16) { it.toByte() }
    private val fastParams = KdfParams(iterations = 1, memoryKib = 8192, parallelism = 1)
    private val password = "correct horse battery staple".toCharArray()
    private val dek = ByteArray(32) { (it * 7).toByte() }
    private val kek = ByteArray(32) { (it * 3).toByte() }

    // ---- deriveKek ----

    @Test
    fun `deriveKek is deterministic`() {
        val a = keyManager.deriveKek(password, salt, fastParams)
        val b = keyManager.deriveKek(password, salt, fastParams)

        assertArrayEquals(a, b)
    }

    @Test
    fun `deriveKek differs for different passwords`() {
        val a = keyManager.deriveKek(password, salt, fastParams)
        val b = keyManager.deriveKek("wrong password".toCharArray(), salt, fastParams)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `deriveKek differs for different salts`() {
        val a = keyManager.deriveKek(password, salt, fastParams)
        val b = keyManager.deriveKek(password, salt.copyOf().also { it[0] = (it[0] + 1).toByte() }, fastParams)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `deriveKek returns 32 bytes for aes-256`() {
        assertEquals(32, keyManager.deriveKek(password, salt, fastParams).size)
    }

    @Test
    fun `deriveKek rejects short salt`() {
        assertThrows(IllegalArgumentException::class.java) {
            keyManager.deriveKek(password, ByteArray(4), fastParams)
        }
    }

    // ---- 规范化编码（S1，经 deriveRecoveryKek 间接验证） ----

    private fun recovery(answers: List<String>): ByteArray =
        keyManager.deriveRecoveryKek(answers.map { it.toCharArray() }, salt)

    @Test
    fun `recovery kek trims surrounding whitespace`() {
        assertArrayEquals(recovery(listOf(" pass ")), recovery(listOf("pass")))
    }

    @Test
    fun `recovery kek prevents concatenation ambiguity`() {
        // 长度前缀使 ["ab","c"] 与 ["a","bc"] 产生不同密钥
        assertFalse(recovery(listOf("ab", "c")).contentEquals(recovery(listOf("a", "bc"))))
    }

    @Test
    fun `recovery kek is case sensitive`() {
        assertFalse(recovery(listOf("Pass")).contentEquals(recovery(listOf("pass"))))
    }

    @Test
    fun `recovery kek rejects blank answer`() {
        assertThrows(IllegalArgumentException::class.java) {
            recovery(listOf("a", "   ", "c"))
        }
    }

    @Test
    fun `recovery kek rejects empty answers`() {
        assertThrows(IllegalArgumentException::class.java) {
            recovery(emptyList())
        }
    }

    // ---- wrap / unwrap ----

    @Test
    fun `wrap unwrap round trip preserves dek`() {
        val wrapped = keyManager.wrapDek(dek, kek)

        assertArrayEquals(dek, keyManager.unwrapDek(wrapped, kek))
    }

    @Test
    fun `unwrap rejects tampered wrapped dek`() {
        val wrapped = keyManager.wrapDek(dek, kek).also {
            it[it.size - 1] = (it[it.size - 1] + 1).toByte()
        }

        assertThrows(KeyUnwrapException::class.java) { keyManager.unwrapDek(wrapped, kek) }
    }

    @Test
    fun `unwrap rejects wrong kek`() {
        val wrapped = keyManager.wrapDek(dek, kek)
        val wrongKek = ByteArray(32) { (it * 11).toByte() }

        assertThrows(KeyUnwrapException::class.java) { keyManager.unwrapDek(wrapped, wrongKek) }
    }

    @Test
    fun `wrap rejects dek that is not 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            keyManager.wrapDek(ByteArray(16), kek)
        }
    }

    // ---- encrypt / decrypt（字段级，D8） ----

    @Test
    fun `encrypt decrypt round trip preserves plaintext`() {
        val plaintext = "P@ssw0rd!密码✓".encodeToByteArray()

        assertArrayEquals(plaintext, keyManager.decrypt(keyManager.encrypt(plaintext, kek), kek))
    }

    @Test
    fun `decrypt rejects tampered ciphertext`() {
        val ciphertext = keyManager.encrypt("secret".encodeToByteArray(), kek)
        val tampered = ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertThrows(AEADBadTagException::class.java) { keyManager.decrypt(tampered, kek) }
    }

    @Test
    fun `encrypt rejects key that is not 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            keyManager.encrypt("data".encodeToByteArray(), ByteArray(16))
        }
    }

    @Test
    fun `encrypt uses random nonce`() {
        val plaintext = "same plaintext".encodeToByteArray()

        assertFalse(keyManager.encrypt(plaintext, kek).contentEquals(keyManager.encrypt(plaintext, kek)))
    }

    @Test
    fun `ciphertext length is plaintext plus nonce and tag`() {
        val plaintext = ByteArray(100) { 5 }

        assertEquals(100 + 12 + 16, keyManager.encrypt(plaintext, kek).size)
    }

    @Test
    fun `key unwrap exception preserves security semantics`() {
        // 统一失败语义：格式错误与 tag 失配对外不可区分（防 oracle）
        val malformed = ByteArray(5)

        assertThrows(KeyUnwrapException::class.java) { keyManager.unwrapDek(malformed, kek) }
        assertTrue(
            KeyUnwrapException::class.java.isAssignableFrom(
                runCatching { keyManager.unwrapDek(malformed, kek) }.exceptionOrNull()!!::class.java,
            ),
        )
    }
}
