package com.zz.passwordmanager.platform.biometric

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * 生物识别诊断（实机排障用）。
 *
 * 背景：G-D 决策把门槛定在 `BIOMETRIC_STRONG`（Class-3），但**部分安卓真机的指纹传感器
 * 不被系统认证为 Class-3**（尤其国产 ROM），表现为「开关切过去直接失败、无法开启」。
 * 光看 `availability()` 只有一个笼统的结论，无法区分究竟是以下哪一种：
 *
 * 1. 硬件/录入本身不可用；
 * 2. 硬件可用但达不到 Class-3 强认证标准；
 * 3. 认证等级够，但 Keystore 建密钥时报错（auth-per-use 与传感器能力不兼容）。
 *
 * 本诊断把三者拆开逐项实测，输出给用户看。
 */
object BiometricDiagnostics {

    data class Report(
        val deviceInfo: String,
        val weakResult: String,
        val strongResult: String,
        val keystoreResult: String,
        val conclusion: String,
    )

    fun run(context: Context): Report {
        val manager = BiometricManager.from(context.applicationContext)
        val weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        val strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        val keystore = probeKeystore()

        val conclusion = when {
            weak != BiometricManager.BIOMETRIC_SUCCESS ->
                "本机生物识别不可用（无硬件或未录入），主密码解锁不受影响。"

            strong == BiometricManager.BIOMETRIC_SUCCESS ->
                "本机生物识别达到强认证标准（Class-3），当前强认证策略可用。"

            keystore.startsWith("失败") ->
                "生物识别已录入但不满足强认证标准，且 Keystore 拒绝创建强认证绑定密钥。" +
                    "当前强认证策略在本机不可用，需要改为兼容模式才能开启。"

            else ->
                "生物识别已录入但不满足强认证标准（Class-3）。" +
                    "这在部分机型上是正常的——系统只把 3D/指纹等认定为强认证。" +
                    "当前强认证策略在本机不可用，需要改为兼容模式才能开启。"

        }

        return Report(
            deviceInfo = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
            weakResult = describe(weak),
            strongResult = describe(strong),
            keystoreResult = keystore,
            conclusion = conclusion,
        )
    }

    /** 实测：能否用 auth-per-use + 强认证创建一把 Keystore 密钥（与生产配置一致）。 */
    private fun probeKeystore(): String = try {
        val alias = "vault-biometric-dek-probe"
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        generator.init(spec)
        generator.generateKey()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        "成功（可创建强认证绑定的 Keystore 密钥）"
    } catch (e: Exception) {
        "失败：${e.javaClass.simpleName} - ${e.message}"
    }

    private fun describe(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "可用（$code）"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "无硬件（$code）"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "硬件暂不可用（$code）"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "未录入（$code）"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "需系统安全更新（$code）"
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "不支持（$code）"
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "状态未知（$code）"
        else -> "其他返回码 $code"
    }
}
