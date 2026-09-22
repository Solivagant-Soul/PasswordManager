package com.zz.passwordmanager.data.crypto

import com.zz.passwordmanager.data.header.KdfParams
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * [KeyManager] 的纯 JVM 实现（无 Android 依赖，单测友好）。
 *
 * - KDF：Argon2id（BC 纯 Java 实现，D3）。生产默认 3 迭代 / 64MiB / 4 并行，
 *   真实参数以 M1 基准校准为准（D3 校准项）；测试注入小参数提速。
 * - 恢复路径 KDF 参数固定为 [DEFAULT_KDF_PARAMS]，不随主密码参数变化——
 *   避免在 Header 存第二份参数（方案 §4.1 Header 无 recoveryKdfParams 字段）。
 * - wrap/字段加密：AES-256-GCM，密文 = nonce(12) ‖ ciphertext+tag(16)；nonce 每次随机（GCM 重用 = 灾难）。
 * - 敏感字节（口令/答案的 UTF-8 临时数组）用后立即清零；
 *   已知局限：CharArray→String 的中间副本无法清零（JVM 固有限制，详见评审记录）。
 */
class Argon2KeyManager(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val recoveryKdfParams: KdfParams = DEFAULT_KDF_PARAMS,
) : KeyManager {

    override fun deriveKek(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.size >= SALT_MIN_BYTES) { "salt must be >= $SALT_MIN_BYTES bytes" }
        val passwordBytes = password.concatToString().encodeToByteArray()
        return try {
            argon2id(passwordBytes, salt, params)
        } finally {
            passwordBytes.fill(0)
        }
    }

    override fun deriveRecoveryKek(answers: List<CharArray>, salt: ByteArray): ByteArray {
        require(salt.size >= SALT_MIN_BYTES) { "salt must be >= $SALT_MIN_BYTES bytes" }
        require(answers.isNotEmpty()) { "answers must not be empty" }
        val encoded = canonicalize(answers)
        return try {
            argon2id(encoded, salt, recoveryKdfParams)
        } finally {
            encoded.fill(0)
        }
    }

    override fun wrapDek(dek: ByteArray, kek: ByteArray): ByteArray {
        require(dek.size == DEK_BYTES) { "dek must be $DEK_BYTES bytes" }
        return encrypt(dek, kek)
    }

    override fun unwrapDek(wrapped: ByteArray, kek: ByteArray): ByteArray =
        try {
            decrypt(wrapped, kek)
        } catch (e: AEADBadTagException) {
            throw KeyUnwrapException("dek unwrap failed: auth tag mismatch", e)
        } catch (e: IllegalArgumentException) {
            throw KeyUnwrapException("dek unwrap failed: malformed input", e)
        }

    override fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes (AES-256)" }
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(data)
    }

    override fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes (AES-256)" }
        require(ciphertext.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = ciphertext.copyOf(NONCE_BYTES)
        val sealed = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(sealed)
    }

    /**
     * 规范化编码（S1）：每答案 Trim → 4 字节大端长度前缀 + UTF-8 字节 → 依序拼接。
     * 长度前缀使 ["ab","c"] 与 ["a","bc"] 产生不同编码；Trim 保证两端归一化一致。
     */
    private fun canonicalize(answers: List<CharArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (answer in answers) {
            val bytes = answer.concatToString().trim().encodeToByteArray()
            require(bytes.isNotEmpty()) { "answers must not be blank after trim" }
            out.write((bytes.size ushr 24) and 0xFF)
            out.write((bytes.size ushr 16) and 0xFF)
            out.write((bytes.size ushr 8) and 0xFF)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
            bytes.fill(0)
        }
        return out.toByteArray()
    }

    private fun argon2id(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKib)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(KEY_BYTES)
        generator.generateBytes(password, out)
        return out
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val DEK_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val SALT_MIN_BYTES = 8

        /** 生产默认（D3：M1 基准校准前的占位值）；T06 初始化 Header 的 kdfParams 也取此值。 */
        val DEFAULT_KDF_PARAMS = KdfParams(iterations = 3, memoryKib = 65536, parallelism = 4)
    }
}
