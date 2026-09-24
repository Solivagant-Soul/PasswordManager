package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.header.VaultHeader
import com.zz.passwordmanager.data.header.VaultHeaderStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 共享认证限流（BR-9 + 找回限流）：主密码与密保找回共用 Header 的
 * failedAttempts/lockUntilEpochMs——两者都是「认证失败」，共享计数更安全（R7 补缺口，2026-09-23 用户拍板）。
 *
 * 本类只计算、不落盘：调用方拿到新 Header 后自行 write（原子，T03）。
 *
 * 参数在构造期校验（评审 G-G）：maxAttempts/lockDurationMs 误配（如 0）会让 BR-9 静默失效，
 * 属「配置错误必须尽早暴露」而非运行期可容忍的情况。
 */
class AuthThrottle(
    private val nowEpochMs: () -> Long,
    val maxAttempts: Int = 5,          // BR-9
    val lockDurationMs: Long = 60_000, // BR-9
) {

    init {
        require(maxAttempts > 0) { "maxAttempts must be > 0, was $maxAttempts (BR-9 将失效)" }
        require(lockDurationMs > 0) { "lockDurationMs must be > 0, was $lockDurationMs (BR-9 将失效)" }
    }

    private val authMutex = Mutex()

    /**
     * 认证临界区（评审 G-A）：把「读 Header → 锁定检查 → 校验 → 失败计数 +1 → 写回」
     * 整段串行化。不加锁时并发尝试（典型触发：解锁按钮连点，每次点击各起一个协程）
     * 会各自读到同一 failedAttempts 再各自写回，丢失失败计数 → BR-9 被削弱；
     * 且并发 Argon2 派生会叠加内存（64MiB × 并发数，见评审 G-G 的 OOM 风险）。
     *
     * 代价：第二次尝试需等第一次完成。相对「锁定被绕过」，这个代价可接受。
     */
    suspend fun <T> serialized(block: suspend () -> T): T = authMutex.withLock { block() }

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
