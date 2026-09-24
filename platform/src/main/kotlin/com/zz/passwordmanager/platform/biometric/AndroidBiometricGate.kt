package com.zz.passwordmanager.platform.biometric

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import com.zz.passwordmanager.domain.biometric.BiometricGate

/**
 * 生物识别可用性实现（T09）：**只接受 Class-3 强认证**（G-D，2026-09-24 用户拍板）。
 *
 * - API 30+：`Authenticators.BIOMETRIC_STRONG`——2D 面容（`BIOMETRIC_WEAK` 可满足）
 *   一律判为不可用，密码库根密钥不交给可能被照片骗过的传感器；
 * - API 26–29：走 deprecated `canAuthenticate()`（androidx 内部已封装兼容，G1）。
 *   **平台限制**：androidx 在 API 29 及以下无法区分认证等级，只能报告「是否有任意生物识别
 *   已录入」。该段系统上纯 2D 面容设备会被判为可用，属框架能力边界，非本实现可弥补；
 *   兜底是 Keystore 侧 `AUTH_BIOMETRIC_STRONG` 密钥要求（`KeystoreDekVault`），API 30+ 会
 *   在加密层再次拒绝弱认证。
 */
class AndroidBiometricGate(context: Context) : BiometricGate {

    private val manager = BiometricManager.from(context.applicationContext)

    override fun availability(): BiometricGate.Availability {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricGate.Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricGate.Availability.NO_HARDWARE
            else -> BiometricGate.Availability.NOT_ENROLLED
        }
    }
}
