package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.domain.biometric.BiometricGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 解锁态会话：持有内存 DEK。T07 由 UnlockVault 创建；lock() 时清零（NFR-S4/T12）。 */
class Session internal constructor(internal val dek: ByteArray) {

    /** 幂等清零：DEK 字节置 0，会话即作废。 */
    fun clear() {
        dek.fill(0)
    }
}

/** 全局锁定状态（方案 §2 核心原则 2）：所有用例入口的解锁态门禁依据（BR-7）。 */
sealed interface VaultState {
    data object Locked : VaultState
    data class Unlocked(val session: Session) : VaultState
}

/** 锁定态访问（P-01/P-06 门禁命中）。 */
class VaultLockedException : Exception("vault is locked")

/** 认证失败统一错误模型：解锁/找回/改密码/密保共用（含 D4 的「失败即判据」语义）。 */
sealed interface AuthError {
    data object VaultMissing : AuthError
    data class LockedUntil(val remainingMs: Long) : AuthError
    data class WrongPassword(val attemptsRemaining: Int) : AuthError
    data class WrongAnswers(val attemptsRemaining: Int) : AuthError
    data class Invalid(val issues: List<Issue>) : AuthError
    data class BiometricUnavailable(val availability: BiometricGate.Availability) : AuthError
    /** 快捷解锁未开启（UI 隐藏入口，不提示）。 */
    data object BiometricNotEnabled : AuthError
    /** 用户取消/点负按钮/单次识别未通过 → 静默降级主密码，不弹错误（不训斥用户）。 */
    data object BiometricCancelled : AuthError
    /** 系统级锁定（多次失败）→ 提示改用主密码，按钮保留。 */
    data object BiometricLockedOut : AuthError
    /** 快捷密钥失效（新录入指纹）或密文校验失败 → 已自愈关闭开关，提示重开。 */
    data object BiometricKeyInvalidated : AuthError

    /**
     * 开启快捷解锁时写入 Keystore 失败（与「硬件不可用/未录入」是**不同**的故障）。
     *
     * v0.10 实机踩坑：包裹失败曾被兜底映射成 `BiometricUnavailable`，而该错误的文案
     * 指向「未录入/无硬件」，把「开启流程写密钥失败」误导成了「设备不支持」，
     * 排查绕了远路。两者必须分开。
     */
    data class BiometricSetupFailed(val reason: String) : AuthError
}

class AuthException(val error: AuthError) : Exception(error.toString())
