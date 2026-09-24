package com.zz.passwordmanager.platform.biometric

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DEK 快捷副本的 Keystore 包裹（T09）：硬件密钥 setUserAuthenticationRequired(true)。
 *
 * **认证策略：每次使用都需认证（auth-per-use，2026-09-24 用户拍板，原为 30s 窗口）**
 * - API 30+：认证有效期 0 秒 + `AUTH_BIOMETRIC_STRONG`——密钥仅在单次 `BiometricPrompt`
 *   认证会话内可用，且必须是 Class-3 强认证（2D 面容直接被拒，见 G-D）；
 * - API 26–29：框架无「每次使用」API，等价语义为 validity = -1（仅生物识别，每次使用都要认证）。
 * - 因此 [unwrap] 不能再自行 init cipher：必须由调用方先 [createDecryptCipher]、
 *   交给 `BiometricPrompt` + `CryptoObject` 完成认证，再把**已认证**的 cipher 传回。
 *   这是 auth-per-use 的安全要点：密钥操作与本次认证绑定，无法在认证后另行使用。
 *
 * 其余语义不变：
 * - 新指纹录入使密钥失效（平台默认）→ 快捷解锁降级主密码（S14）；
 * - wrap 无需认证（持有效主密码会话即可写），unwrap 需认证通过。
 */
class KeystoreDekVault {

    fun wrap(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val sealed = cipher.doFinal(dek)
        return cipher.iv + sealed
    }

    /**
     * 为 `BiometricPrompt` 准备待认证的解密 cipher：
     * `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))`，成功后取
     * `result.cryptoObject!!.cipher` 传给 [unwrap]。未认证时本方法即会抛
     * `UserNotAuthenticatedException`（auth-per-use 的预期行为）。
     */
    fun createDecryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, getOrCreateKey()) }

    /**
     * 用**已通过认证**的 cipher 解出 DEK。
     * 传入未经认证的 cipher 会被 Keystore 拒绝（抛 UserNotAuthenticatedException）。
     */
    fun unwrap(wrapped: ByteArray, authenticatedCipher: Cipher): ByteArray {
        authenticatedCipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, wrapped.copyOf(NONCE_BYTES)),
        )
        return authenticatedCipher.doFinal(wrapped.copyOfRange(NONCE_BYTES, wrapped.size))
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
