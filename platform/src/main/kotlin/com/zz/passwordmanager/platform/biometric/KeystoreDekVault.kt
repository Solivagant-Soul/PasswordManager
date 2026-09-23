package com.zz.passwordmanager.platform.biometric

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DEK 快捷副本的 Keystore 包裹（T09）：硬件密钥 setUserAuthenticationRequired(true)。
 *
 * - 30 秒认证窗口：API 30+ 用 AUTH_BIOMETRIC_STRONG；API 26–29 用 validity duration（G1）；
 * - 新指纹录入使密钥失效（平台默认）→ 快捷解锁降级主密码（S14）；
 * - wrap 无需认证（持有效主密码会话即可写），unwrap 需认证通过——androidTest
 *   断言未认证时抛 UserNotAuthenticatedException（安全语义验证）。
 */
class KeystoreDekVault {

    fun wrap(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val sealed = cipher.doFinal(dek)
        return cipher.iv + sealed
    }

    /** 需处于生物识别认证有效期；未认证抛 UserNotAuthenticatedException。 */
    fun unwrap(wrapped: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped.copyOf(NONCE_BYTES)))
        return cipher.doFinal(wrapped.copyOfRange(NONCE_BYTES, wrapped.size))
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
                    setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(30)
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
