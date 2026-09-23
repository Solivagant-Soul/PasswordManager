package com.zz.passwordmanager.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.passwordmanager.platform.biometric.KeystoreDekVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T09 运行时验收（需设备/模拟器）：
 * 1. wrap 无需认证即可加密；
 * 2. 未通过生物识别认证时 unwrap 必须被 Keystore 拒绝（安全语义）。
 *
 * 无已录入生物特征的设备（headless 模拟器）上跳过 auth 绑定用例——
 * Keystore 拒绝生成此类密钥；生产路径由 BiometricToggle 的 Gate 检查先行拦截。
 * 执行：./gradlew :platform:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDekVaultTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = KeystoreDekVault()
    private val dek = ByteArray(32) { it.toByte() }

    @Before
    fun assumeBiometricEnrolled() {
        val result = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        Assume.assumeTrue(
            "skip: no biometric enrolled on this device (gate would block users too)",
            result == BiometricManager.BIOMETRIC_SUCCESS,
        )
    }

    @Test
    fun wrapProducesCiphertextWithIvPrefix() {
        val wrapped = vault.wrap(dek)

        assertTrue(wrapped.size > 32 + 12 + 16)
    }

    @Test
    fun unwrapWithoutAuthenticationIsRejectedByKeystore() {
        val wrapped = vault.wrap(dek)

        // 刚生成/未在认证窗口内 → 必须要求用户认证
        assertThrows(UserNotAuthenticatedException::class.java) { vault.unwrap(wrapped) }
    }

    @Test
    fun wrapUsesRandomIv() {
        // 认证窗口内的完整往返需 BiometricPrompt（UI 层触发）；
        // 此处验证 wrap 的随机 IV 语义（同明文两次密文不同）。
        val a = vault.wrap(dek)
        val b = vault.wrap(dek)

        assertTrue(!a.contentEquals(b))
    }

    /** AndroidKeyStore 基建冒烟：非 auth 绑定密钥的 GCM 往返（不依赖生物识别录入）。 */
    @Test
    fun keystoreInfrastructureRoundTripWithoutAuth() {
        val alias = "t09-ks-baseline"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generator.generateKey()
        }
        val key = keyStore.getKey(alias, null) as SecretKey

        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, key)
        val sealed = encrypt.iv + encrypt.doFinal(dek)

        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOf(12)))
        assertArrayEquals(dek, decrypt.doFinal(sealed.copyOfRange(12, sealed.size)))
    }
}
