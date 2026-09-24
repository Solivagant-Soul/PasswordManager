package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 解锁用例（FR-2/T07，方案 §6.2）：读 Header → 限流检查 → 派生 → unwrap → 成功重置/失败+1。
 *
 * Header 完整性异常（R11）不在此捕获透传——那是「库被篡改」而非「密码错误」。
 * 整个「读-校验-写回」在 [AuthThrottle.serialized] 临界区内（评审 G-A）；
 * Argon2 派生在 [computeDispatcher] 上执行，不占用调用方线程（M2 遗留 D-1）。
 */
class UnlockVault(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend operator fun invoke(password: CharArray): Result<Session> =
        throttle.serialized { withContext(computeDispatcher) { authenticate(password) } }

    private fun authenticate(password: CharArray): Result<Session> {
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
