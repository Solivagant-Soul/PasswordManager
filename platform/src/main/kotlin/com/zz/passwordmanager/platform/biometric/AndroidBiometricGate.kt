package com.zz.passwordmanager.platform.biometric

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import com.zz.passwordmanager.domain.biometric.BiometricGate

/**
 * 生物识别可用性实现（T09）：API 30+ 用 Authenticators.BIOMETRIC_WEAK，
 * API 26–29 走 deprecated canAuthenticate()（androidx 内部已封装兼容，G1）。
 */
class AndroidBiometricGate(context: Context) : BiometricGate {

    private val manager = BiometricManager.from(context.applicationContext)

    override fun availability(): BiometricGate.Availability {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
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
