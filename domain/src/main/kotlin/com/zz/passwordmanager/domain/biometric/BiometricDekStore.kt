package com.zz.passwordmanager.domain.biometric

import javax.crypto.Cipher

/**
 * 生物识别 DEK 快捷副本的存储端口（FR-2/T09）。实现在 `:platform`（Keystore 适配）。
 *
 * **为什么需要这个端口**：`:data` 依赖方向是 `data|platform → domain`，不依赖 `:platform`，
 * 而 `BiometricToggle`（在 :data）需要调用 Keystore 包裹 DEK。按既有 `BiometricGate` 端口
 * 模式解开依赖，与 `AndroidBiometricGate : BiometricGate` 同构。
 *
 * **为什么签名里出现 [Cipher]**：它是 JDK 类型（非 Android），纯 JVM 测试可构造；
 * 且 auth-per-use 的时序（认证前建 cipher → 系统认证 → 用**已认证**的那把 cipher 解密）
 * 无法用更抽象的类型表达——加密对象与本次认证绑定正是这一策略的全部要点。
 */
interface BiometricDekStore {

    /**
     * 为 `BiometricPrompt` + `CryptoObject` 预建**加密** cipher。
     * 与 [createDecryptCipher] 同样必须在认证**之前**调用。
     */
    fun createEncryptCipher(): Cipher

    /**
     * 包裹 DEK 快捷副本，用**已通过认证**的加密 cipher。
     *
     * 之所以也要认证：Android 对 `setUserAuthenticationRequired(true)` 的密钥，
     * **加密与解密同样要求认证**（v0.10 实机踩坑：曾假设「开启时已验过主密码，
     * 包裹就不必认证」——该假设是错的，导致开启直接失败）。因此开启快捷解锁
     * 本身就要求先做一次生物识别认证。
     */
    fun wrap(dek: ByteArray, authenticatedCipher: Cipher): ByteArray

    /**
     * 为 `BiometricPrompt` + `CryptoObject` 预建解密 cipher。
     *
     * **必须在任何用户认证之前调用**（密钥认证有效期为 0 秒 / validity -1）。
     *
     * @param wrapped 待解的密文（`nonce‖密文+tag`）。GCM 解密**必须连 IV 一起初始化**，
     *   故 IV 只能从待解密文中取——这要求调用方先拿到挑战密文再建 cipher。
     *   漏掉 IV 会得到 `InvalidKeyException: iv required when decrypting`（v0.10 实机踩坑）。
     */
    fun createDecryptCipher(wrapped: ByteArray): Cipher

    /**
     * 用**已通过认证**的 cipher 解出 DEK。
     * 传入未经认证的 cipher 会被 Keystore 拒绝。
     *
     * 实现方**不得重新 init** 该 cipher：`CryptoObject` 带来的认证状态绑定在这次
     * 初始化上，重新 init 会冲掉它。
     *
     * @throws BiometricKeyInvalidatedException 密钥被系统作废（新录入指纹等）或密文校验失败
     */
    fun unwrap(wrapped: ByteArray, authenticatedCipher: Cipher): ByteArray

    /** 删除 Keystore 密钥（关闭开关 / 清除重建），避免留下无主凭证。 */
    fun deleteKey()
}

/** 快捷密钥失效：新录入生物特征会使旧密钥永久失效，此时须自愈降级主密码（S14）。 */
class BiometricKeyInvalidatedException(message: String, cause: Throwable? = null) : Exception(message, cause)
