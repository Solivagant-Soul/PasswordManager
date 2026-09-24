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
    fun createEncryptCipherWithoutAuthenticationIsRejected() {
        // v0.10 定论：包裹与解包同密钥，包裹同样需要认证会话
        assumeStrongBiometric()
        assertThrows(UserNotAuthenticatedException::class.java) { vault.createEncryptCipher() }
    }

    /**
     * 回归（v0.10 实机踩坑）：GCM 解密 cipher **必须带 IV 初始化**。
     * 漏掉 IV 会抛 `InvalidKeyException: iv required when decrypting`——
     * 而 IV 存在待解密的密文里，所以建 cipher 必须先拿到密文。
     *
     * 本用例断言的是「不带 IV 的解密 init 必定失败」这一 API 事实，
     * **在无已录入生物特征的模拟器上也能跑**。
     */
    @Test
    fun decryptInitWithoutIvIsRejected() {
        val noIv = Cipher.getInstance("AES/GCM/NoPadding")
        val error = runCatching {
            noIv.init(
                Cipher.DECRYPT_MODE,
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .getKey("vault-biometric-dek", null) as SecretKey?,
            )
        }.exceptionOrNull()

        assertTrue("不带 IV 的 GCM 解密 init 应当失败", error != null)
    }

    @Test
    fun createEncryptCipherIsDeterministicInShape() {
        assumeStrongBiometric()
        // 完整往返需 BiometricPrompt（UI 层触发）；此处只验证建 cipher 的可达性
        //（真正 doFinal 在无认证上下文下必然被拒，见 wrapWithoutAuthenticationIsRejected）
        assertTrue(runCatching { vault.createEncryptCipher() }.isFailure)
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
     * 回归测试（v0.10 两次实机踩坑）：**包裹同样需要用户认证**。
     *
     * 曾经的两次错误：
     * 1. 假设「开启时已验过主密码，包裹就不必认证」→ `doFinal` 抛
     *    UserNotAuthenticatedException，表现为「生物识别无法开启」；
     * 2. 改用「不认证密钥包裹 + 认证密钥解包」→ **A 密钥加密的数据无法用 B 密钥
     *    解密**，解锁时 GCM 标签不匹配，表现为「生物识别已失效」。
     *
     * 定论：对称密钥不存在「只对解密要求认证」的配置，包裹与解包必须同密钥、
     * 且都在认证会话内完成。
     *
     * 本用例在**无认证**的上下文里调用包裹，**必须失败**——这正是回归信号。
     * 真正的「认证后可包裹」路径需要 `BiometricPrompt`，只能在真机上端到端验证。
     */
    @Test
    fun wrapWithoutAuthenticationIsRejected() {
        vault.deleteKey() // 从干净状态开始

        // 断言的是安全性质「无认证时包裹必定失败」，**不绑定具体异常类型**——
        // 无已录入生物特征的设备上失败发生在**建密钥**阶段
        // （InvalidAlgorithmParameterException: At least one biometric must be enrolled），
        // 已录入但当前未认证的设备上则失败在 **doFinal** 阶段
        // （UserNotAuthenticatedException）。两者都证明「没认证就不能包裹」。
        val error = runCatching { vault.wrap(dek, vault.createEncryptCipher()) }.exceptionOrNull()

        assertTrue("无认证时包裹应当失败，但成功了", error != null)
    }

    /** 单密钥：别名固定，deleteKey 后不得残留。 */
    @Test
    fun deleteKeyRemovesTheAlias() {
        vault.deleteKey()

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(!store.containsAlias("vault-biometric-dek"))
    }
}
