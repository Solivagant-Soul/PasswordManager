package com.zz.passwordmanager.data.biometric

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.data.vault.Issue
import com.zz.passwordmanager.domain.biometric.BiometricGate

/**
 * 生物识别开关（T09/P-03）：验证当前主密码方可开启/关闭；
 * 开启时须硬件可用且已录入（否则 BiometricUnavailable）。
 */
class BiometricToggle(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val gate: BiometricGate,
) {

    operator fun invoke(enable: Boolean, masterPassword: CharArray): Result<Unit> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return Result.failure(AuthException(AuthError.LockedUntil(it)))
        }

        // P-03：验证当前主密码（失败计入共享限流）
        try {
            val kek = keyManager.deriveKek(masterPassword, header.saltKdf, header.kdfParams)
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

        if (enable && gate.availability() != BiometricGate.Availability.AVAILABLE) {
            return Result.failure(
                AuthException(AuthError.BiometricUnavailable(gate.availability())),
            )
        }

        headerStore.write(throttle.onAuthSuccess(header.copy(biometricEnabled = enable)))
        return Result.success(Unit)
    }
}
