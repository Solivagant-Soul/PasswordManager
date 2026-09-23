package com.zz.passwordmanager.domain.biometric

/**
 * 生物识别可用性端口（FR-2/T09）。
 * 实现在 :platform（AndroidBiometricGate，含 API 26–29 / 30+ 分支，G1）；
 * 开启/关闭的权限门（P-03 验主密码）在 :data BiometricToggle。
 */
interface BiometricGate {

    fun availability(): Availability

    enum class Availability { AVAILABLE, NO_HARDWARE, NOT_ENROLLED }
}
