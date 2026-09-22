package com.zz.passwordmanager.data.crypto

import com.zz.passwordmanager.data.header.KdfParams

/**
 * KDF / 密钥包裹 / AEAD 的统一门面（技术方案 §5）。实现见 [Argon2KeyManager]。
 *
 * 这是 M1 安全关键路径的核心抽象：T07 解锁、T10 密保找回都以
 * 「能否用给定口令/答案 [unwrapDek]」作为对错判据（D4）。
 */
interface KeyManager {

    /** 主密码 → KEK_master（Argon2id）。 */
    fun deriveKek(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray

    /**
     * 密保答案 → KEK_recovery（找回/重置路径，方案 §4.2）。
     * 内部做规范化编码（S1）：Trim → 大端 4 字节长度前缀 + UTF-8 → 依序拼接 → Argon2id；
     * 设置与找回两端共用本函数，杜绝拼接歧义与归一化漂移。
     */
    fun deriveRecoveryKek(answers: List<CharArray>, salt: ByteArray): ByteArray

    /** 用 KEK 包裹 DEK（AES-256-GCM：nonce(12) ‖ ciphertext+tag）。 */
    fun wrapDek(dek: ByteArray, kek: ByteArray): ByteArray

    /**
     * 解出 DEK。
     * @throws KeyUnwrapException KEK 错误（即主密码/答案错误）或密文被篡改
     */
    fun unwrapDek(wrapped: ByteArray, kek: ByteArray): ByteArray

    /** 字段级加密（D8：passwordCipher/totpSecretCipher）。密文 = nonce(12) ‖ ciphertext+tag。 */
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray

    /** 字段级解密。@throws AEADBadTagException 密文被篡改 */
    fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray
}

/** unwrap 失败——统一失败语义，不泄露内部细节（防 oracle）。 */
class KeyUnwrapException(message: String, cause: Throwable? = null) : Exception(message, cause)
