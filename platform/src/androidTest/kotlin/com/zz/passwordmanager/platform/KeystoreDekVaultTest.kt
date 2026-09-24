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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T09 运行时验收（需设备/模拟器）：
 * 1. wrap 无需认证即可加密；
 * 2. 未通过认证时不能取到解密 cipher（auth-per-use 安全语义，G-E）。
 *
 * 需已录入 **Class-3 强认证**（G-D，与生产门禁一致）的设备才跑得起来——
 * headless 模拟器无生物特征，Keystore 会拒绝生成 auth 绑定密钥。
 * 生产路径由 `BiometricToggle` 的 Gate 检查先行拦截，不会让这类设备走到这里。
 *
 * 注意：`assumeStrongBiometric()` 只放在**依赖生物识别**的用例里，
 * 不放 @Before——否则连不依赖生物识别的 Keystore 基建冒烟用例也会被一并跳过。
 *
 * 执行：./gradlew :platform:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDekVaultTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = KeystoreDekVault()
    private val dek = ByteArray(32) { it.toByte() }

    /** 跳过条件：无 Class-3 强认证时，Keystore 拒绝创建 auth 绑定密钥。 */
    private fun assumeStrongBiometric() {
        val result = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        Assume.assumeTrue(
            "skip: no strong biometric enrolled on this device (gate would block users too)",
            result == BiometricManager.BIOMETRIC_SUCCESS,
        )
    }

    @Test
    fun wrapProducesCiphertextWithIvPrefix() {
        assumeStrongBiometric()
        val wrapped = vault.wrap(dek)

        assertTrue(wrapped.size > 32 + 12 + 16)
    }

    @Test
    fun creatingDecryptCipherWithoutAuthenticationIsRejected() {
        assumeStrongBiometric()
        // G-E auth-per-use：连 init 解密 cipher 都要求本次认证，密钥不在任何缓存窗口内
        assertThrows(UserNotAuthenticatedException::class.java) { vault.createDecryptCipher() }
    }

    @Test
    fun wrapUsesRandomIv() {
        assumeStrongBiometric()
        // 完整往返需 BiometricPrompt（UI 层触发）；此处验证 wrap 的随机 IV 语义。
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

    /**
     * 回归测试（v0.10 实机踩坑）：包裹 DEK **不应要求用户认证**。
     *
     * 曾经的 bug：包裹与解包共用一把 `setUserAuthenticationRequired(true)` 的密钥，
     * 而开启快捷解锁时用户刚验的是主密码、并非生物识别——Android 对 auth-required
     * 密钥的**加密同样要求认证**，于是 `doFinal` 抛 UserNotAuthenticatedException，
     * 表现为「生物识别无法开启」。
     *
     * 修复后包裹走独立的、不需认证的密钥，**本用例无需录入生物特征即可运行**，
     * 因此能在 CI/模拟器上守住这条回归（原实现下本例会失败）。
     */
    @Test
    fun wrapDoesNotRequireUserAuthentication() {
        val wrapped = vault.wrap(dek)

        assertTrue(wrapped.size > dek.size)
        // 能在无任何认证的情况下完成加密，即证明包裹密钥不要求认证
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("vault-biometric-dek-wrap", null) is SecretKey)
    }

    /** 开启后必须清掉**两把**密钥：包裹与解包，漏一把即留下无主凭证。 */
    @Test
    fun deleteKeyRemovesBothAliases() {
        vault.wrap(dek)
        vault.deleteKey()

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(!store.containsAlias("vault-biometric-dek"))
        assertTrue(!store.containsAlias("vault-biometric-dek-wrap"))
    }
}
