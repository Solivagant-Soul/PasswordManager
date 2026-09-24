package com.zz.passwordmanager.platform.biometric

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricKeyInvalidatedException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DEK 快捷副本的 Keystore 包裹（T09）。
 *
 * **单密钥，认证后才可使用**（v0.10 两次实机踩坑后的定论，勿再改成多密钥）：
 * - 一次 auth-per-use + `AUTH_BIOMETRIC_STRONG` 的 AES-256 密钥；
 * - Android 对 `setUserAuthenticationRequired(true)` 的密钥，**加密与解密同样要求认证**；
 * - 因此 [wrap] 与 [unwrap] 都必须传入**已通过认证**的 cipher，
 *   对应 [createEncryptCipher] / [createDecryptCipher] 两条「认证前建 cipher」路径；
 * - 代价：开启快捷解锁时需额外做一次生物识别认证。
 *
 * 踩坑记录（勿重犯）：
 * 1. 曾假设「开启时已验过主密码，包裹就不必认证」→ 开启必然失败；
 * 2. 曾改用「不认证密钥包裹 + 认证密钥解包」→ **A 密钥加密的数据无法用 B 密钥解密**，
 *    解锁时 GCM 标签不匹配，表现为「生物识别已失效」。对称密钥不存在
 *    「只对解密要求认证」的配置。
 *
 * 其余语义：
 * - **只接受 Class-3 强认证**（G-D），2D 面容被拒；
 * - 新录入指纹会使密钥永久失效 → [BiometricKeyInvalidatedException]，调用方自愈降级主密码（S14）。
 */


class KeystoreDekVault : BiometricDekStore {

    override fun createEncryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    /**
     * 用**已通过认证**的加密 cipher 包裹 DEK。
     *
     * v0.10 两次踩坑的教训：
     * 1. 曾用**不认证**的独立密钥包裹（以为可以免掉开启时的生物识别）——
     *    但用 A 密钥加密的数据无法用 B 密钥解密，解锁时 GCM 标签必不匹配，
     *    表现为「生物识别已失效」。**对称密钥不存在「只对解密要求认证」的配置**。
     * 2. 正确做法是**同一把密钥**，加密解密都在生物识别认证会话内完成，
     *    代价是开启快捷解锁时要多验一次指纹。
     *
     * 认证后的 cipher 可直接使用：IV 由 Keystore 在首次使用时生成。
     */
    override fun wrap(dek: ByteArray, authenticatedCipher: Cipher): ByteArray {
        val iv = authenticatedCipher.iv // doFinal 之前取，避免依赖 doFinal 后的状态
        val sealed = authenticatedCipher.doFinal(dek)
        return iv + sealed
    }

    /**
     * 为 `BiometricPrompt` 准备待认证的解密 cipher：
     * `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))`，成功后取
     * `result.cryptoObject!!.cipher` 传给 [unwrap]。未认证时本方法即会抛
     * `UserNotAuthenticatedException`（auth-per-use 的预期行为）。
     */
    override fun createDecryptCipher(wrapped: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            // GCM 解密必须连 IV 一起初始化；漏掉 IV 会抛
            // InvalidKeyException: iv required when decrypting（v0.10 实机踩坑）
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, wrapped.copyOf(NONCE_BYTES)))
        }

    /**
     * 用**已通过认证**的 cipher 解出 DEK。
     * 传入未经认证的 cipher 会被 Keystore 拒绝（抛 UserNotAuthenticatedException）。
     *
     * 密钥失效（新录入指纹）或 GCM 校验失败（密文被篡改）都归一为
     * [BiometricKeyInvalidatedException]：调用方据此自愈关闭开关并降级主密码。
     * 不把底层异常类型泄给调用方，避免把「篡改」与「密钥失效」区分开来成为 oracle。
     */
    override fun unwrap(wrapped: ByteArray, authenticatedCipher: Cipher): ByteArray = try {
        // **不得重新 init**：`CryptoObject` 认证带来的授权绑定在 createDecryptCipher 的那次
        // 初始化上，重新 init 会把它冲掉，doFinal 随即抛 UserNotAuthenticatedException。
        // IV 已在建 cipher 时提供，这里只做 doFinal。
        authenticatedCipher.doFinal(wrapped.copyOfRange(NONCE_BYTES, wrapped.size))
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw BiometricKeyInvalidatedException("keystore key permanently invalidated", e)
    } catch (e: AEADBadTagException) {
        throw BiometricKeyInvalidatedException("biometric-wrapped dek failed authentication", e)
    }

    /**
     * 删除 Keystore 密钥别名（包裹与解包两把）。幂等：不存在时静默返回。
     * 顺序无关，但都必须在「先清 Header 再删 key」之后——反过来的崩溃窗口会留下
     * 「开关开着、密钥没了」的永久锁死状态（见 BiometricToggle）。
     */
    override fun deleteKey() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 0 = 每次使用都需重新认证（G-E）
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    // API 26–29 无「每次使用」API；-1 = 每次使用都需生物识别
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        const val ALIAS = "vault-biometric-dek"

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
