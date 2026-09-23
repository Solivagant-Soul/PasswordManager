package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore

/**
 * 解锁用例（FR-2/T07，方案 §6.2）：读 Header → 限流检查 → 派生 → unwrap → 成功重置/失败+1。
 *
 * Header 完整性异常（R11）不在此捕获透传——那是「库被篡改」而非「密码错误」。
 */
class UnlockVault(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
) {

    operator fun invoke(password: CharArray): Result<Session> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return Result.failure(AuthException(AuthError.LockedUntil(it)))
        }

        val dek = try {
            val kek = keyManager.deriveKek(password, header.saltKdf, header.kdfParams)
            keyManager.unwrapDek(header.wrappedDekMaster, kek)
        } catch (e: KeyUnwrapException) {
            val outcome = throttle.onAuthFailure(header)
            headerStore.write(outcome.newHeader)
            return Result.failure(
                AuthException(
                    if (outcome.lockTriggered) AuthError.LockedUntil(outcome.remainingMs)
                    else AuthError.WrongPassword((throttle.maxAttempts - (header.failedAttempts + 1)).coerceAtLeast(0)),
                ),
            )
        }

        headerStore.write(throttle.onAuthSuccess(header))
        return Result.success(Session(dek))
    }
}
