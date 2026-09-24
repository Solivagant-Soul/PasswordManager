package com.zz.passwordmanager.data.biometric

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 生物识别开关（T09/P-03）：验证当前主密码方可开启/关闭；
 * 开启时须硬件可用且已录入（否则 BiometricUnavailable）。
 * 与其余认证用例同构，故同样在 [AuthThrottle.serialized] 临界区内执行（评审 G-A）。
 */
class BiometricToggle(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val gate: BiometricGate,
    private val dekStore: BiometricDekStore,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend operator fun invoke(enable: Boolean, masterPassword: CharArray): Result<Unit> =
        throttle.serialized { withContext(computeDispatcher) { toggle(enable, masterPassword) } }

    private fun toggle(enable: Boolean, masterPassword: CharArray): Result<Unit> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return Result.failure(AuthException(AuthError.LockedUntil(it)))
        }

        // P-03：验证当前主密码（失败计入共享限流）；解出的 DEK 正是待包裹的快捷副本
        val dek = try {
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

        // 包裹失败与「硬件不可用/未录入」是不同的故障，必须分开报——
        // v0.10 前一律映射成 BiometricUnavailable，把写入失败说成了设备不支持
        val wrapped = if (enable) {
            runCatching { dekStore.wrap(dek) }.getOrElse { e ->
                return Result.failure(
                    AuthException(
                        AuthError.BiometricSetupFailed("${e.javaClass.simpleName}: ${e.message}"),
                    ),
                )
            }
        } else {
            null
        }

        // 开关位与被包裹密文**一次原子写**：避免「开关开了但密文没写成功」的中间态
        headerStore.write(
            throttle.onAuthSuccess(
                header.copy(biometricEnabled = enable, wrappedDekBiometric = wrapped),
            ),
        )
        // 先清 Header 再删 Keystore 密钥：反过来的崩溃窗口会留下「开关开着、密钥没了」
        // 的永久锁死状态。关闭时密钥是残留的无主凭证，必须清。
        if (!enable) {
            runCatching { dekStore.deleteKey() }
        }
        return Result.success(Unit)
    }
}
