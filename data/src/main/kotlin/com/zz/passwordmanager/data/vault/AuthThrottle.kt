package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.header.VaultHeader
import com.zz.passwordmanager.data.header.VaultHeaderStore

/**
 * 共享认证限流（BR-9 + 找回限流）：主密码与密保找回共用 Header 的
 * failedAttempts/lockUntilEpochMs——两者都是「认证失败」，共享计数更安全（R7 补缺口，2026-09-23 用户拍板）。
 *
 * 本类只计算、不落盘：调用方拿到新 Header 后自行 write（原子，T03）。
 */
class AuthThrottle(
    private val nowEpochMs: () -> Long,
    val maxAttempts: Int = 5,          // BR-9
    val lockDurationMs: Long = 60_000, // BR-9
) {

    /** 锁定期内返回剩余毫秒；未锁返回 null。 */
    fun lockedRemainingMs(header: VaultHeader): Long? =
        header.lockUntilEpochMs.takeIf { it > nowEpochMs() }?.let { it - nowEpochMs() }

    /** 认证失败结果：新 Header + 是否触发锁定（feature 02：第 5 次失败即锁定并提示剩余时间）。 */
    data class FailureOutcome(
        val newHeader: VaultHeader,
        val lockTriggered: Boolean,
        val remainingMs: Long,
    )

    /** 认证失败：计数 +1；达到阈值（含等号，第 5 次错误）即写入锁定时钟。 */
    fun onAuthFailure(header: VaultHeader): FailureOutcome {
        val attempts = header.failedAttempts + 1
        return if (attempts >= maxAttempts) {
            val lockedUntil = nowEpochMs() + lockDurationMs
            FailureOutcome(
                header.copy(failedAttempts = attempts, lockUntilEpochMs = lockedUntil),
                lockTriggered = true,
                remainingMs = lockDurationMs,
            )
        } else {
            FailureOutcome(
                header.copy(failedAttempts = attempts, lockUntilEpochMs = 0L),
                lockTriggered = false,
                remainingMs = 0L,
            )
        }
    }

    /** 认证成功：计数与锁定时钟归零。 */
    fun onAuthSuccess(header: VaultHeader): VaultHeader =
        header.copy(failedAttempts = 0, lockUntilEpochMs = 0L)
}
