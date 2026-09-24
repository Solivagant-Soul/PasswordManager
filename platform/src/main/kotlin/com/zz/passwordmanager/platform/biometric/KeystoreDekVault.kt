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
 * **用两把密钥**（v0.10 实机踩坑后修正，勿合并为一把）：
 * - 解包密钥 `vault-biometric-dek`：auth-per-use + `AUTH_BIOMETRIC_STRONG`，
 *   仅用于解出 DEK，是快捷解锁的真实安全边界；
 * - 包裹密钥 `vault-biometric-dek-wrap`：**不需认证**，仅用于写入快捷副本。
 *
 * 为什么要分两把：Android 对 `setUserAuthenticationRequired(true)` 的密钥，
 * **加密与解密同样要求用户认证**。而开启快捷解锁时用户刚验证的是**主密码**，
 * 此刻并没有生物识别认证——若包裹也用需认证密钥，`doFinal` 必抛
 * `UserNotAuthenticatedException`，表现为「生物识别无法开启」。
 * 这条假设在 M1 写下时标注为「wrap 无需认证（持有效主密码会话即可写）」，
 * **该假设是错的，直到真机才暴露**（见 v0.10 处置记录）。
 *
 * 其余语义：
 * - auth-per-use（G-E）：API 30+ 认证有效期 0 秒，26–29 用 validity = -1；
 * - **只接受 Class-3 强认证**（G-D），2D 面容被拒；
 * - [unwrap] 接收**已认证**的 cipher（auth-per-use 的时序要求，见 [createDecryptCipher]）；
 * - 新录入指纹会使解包密钥永久失效 → [BiometricKeyInvalidatedException]，调用方自愈降级主密码（S14）。
 */

class KeystoreDekVault : BiometricDekStore {

    override fun wrap(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 用**不需认证的包裹密钥**：开启快捷解锁时用户刚验的是主密码而非生物识别，
        // 若此处也用 auth-required 密钥，Android 会要求本次生物识别认证
        // （auth-required 密钥的**加密操作同样需要认证**），开启流程必然失败。
        // 实机踩坑记录：v0.10 前此处用 auth-required 密钥，导致「生物识别无法开启」，
        // 而错误被兜底映射成 BiometricUnavailable，提示语误导为「未录入/无硬件」。
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val sealed = cipher.doFinal(dek)
        return cipher.iv + sealed
    }

    /**
     * 为 `BiometricPrompt` 准备待认证的解密 cipher：
     * `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))`，成功后取
     * `result.cryptoObject!!.cipher` 传给 [unwrap]。未认证时本方法即会抛
     * `UserNotAuthenticatedException`（auth-per-use 的预期行为）。
     */
    override fun createDecryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, getOrCreateKey()) }

    /**
     * 用**已通过认证**的 cipher 解出 DEK。
     * 传入未经认证的 cipher 会被 Keystore 拒绝（抛 UserNotAuthenticatedException）。
     *
     * 密钥失效（新录入指纹）或 GCM 校验失败（密文被篡改）都归一为
     * [BiometricKeyInvalidatedException]：调用方据此自愈关闭开关并降级主密码。
     * 不把底层异常类型泄给调用方，避免把「篡改」与「密钥失效」区分开来成为 oracle。
     */
    override fun unwrap(wrapped: ByteArray, authenticatedCipher: Cipher): ByteArray = try {
        authenticatedCipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, wrapped.copyOf(NONCE_BYTES)),
        )
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
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.run {
            deleteEntry(UNWRAP_ALIAS)
            deleteEntry(WRAP_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey = key(UNWRAP_ALIAS, requireAuth = true)

    /**
     * 包裹密钥：**不要求用户认证**。
     *
     * 只在 [wrap]（开启快捷解锁时）使用，而该路径已由 [com.zz.passwordmanager.data.biometric.BiometricToggle]
     * 强制先验证当前主密码。攻击者即便能调用本类的 wrap，也需要先拿到 DEK 明文，
     * 而取 DEK 明文必须用需认证的解包密钥——故此处放宽不构成新的暴露面。
     */
    private fun wrapKey(): SecretKey = key(WRAP_ALIAS, requireAuth = false)

    private fun key(alias: String, requireAuth: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(requireAuth)
            .apply {
                if (requireAuth) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // 0 = 每次使用都需重新认证（G-E）
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        // API 26–29 无「每次使用」API；-1 = 每次使用都需生物识别
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** 需认证：auth-per-use + 强认证，只用于解包（快捷解锁的真实安全边界） */
        const val UNWRAP_ALIAS = "vault-biometric-dek"

        /** 不需认证：只用于包裹，开启快捷解锁时使用（见 [wrapKey]） */
        const val WRAP_ALIAS = "vault-biometric-dek-wrap"

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
