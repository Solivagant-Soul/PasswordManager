package com.zz.passwordmanager.data.biometric

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricGate
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/**
 * 生物识别开关（T09/P-03）。
 *
 * **开启是两段的**（v0.10 实机踩坑后定）：`BiometricDekStore.wrap` 与 `unwrap` 都需要
 * 一把**已通过生物识别认证**的 cipher——Android 对 `setUserAuthenticationRequired(true)`
 * 的密钥，加密与解密同样要求认证。所以流程是：
 *
 * 1. [prepareEnable] 验主密码 + 查硬件 → 返回待包裹的 DEK（不进临界区，
 *    因为随后要弹 BiometricPrompt，持锁会阻塞用户用主密码解锁）；
 * 2. :app 驱动 `BiometricPrompt` + `CryptoObject` 认证；
 * 3. [completeEnable] 在临界区内用**已认证**的 cipher 包裹并落盘。
 *
 * 关闭仍是一段式：[disable] 验主密码 → 清 Header 字段 + 删 Keystore 密钥。
 *
 * 开关位与被包裹密文**一次原子写**，避免「开关开了但密文没写成功」的中间态。
 */
class BiometricToggle(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val gate: BiometricGate,
    private val dekStore: BiometricDekStore,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** 待包裹的 DEK。完成或取消后应调用 [clear] 及时清零。 */
    class EnableSetup internal constructor(internal val dek: ByteArray) {
        fun clear() = dek.fill(0)
    }

    /** 开启第一段：验主密码 + 硬件门禁，返回待包裹的 DEK。 */
    suspend fun prepareEnable(masterPassword: CharArray): Result<EnableSetup> =
        withContext(computeDispatcher) {
            val header = headerStore.read()
                ?: return@withContext Result.failure(AuthException(AuthError.VaultMissing))

            throttle.lockedRemainingMs(header)?.let {
                return@withContext Result.failure(AuthException(AuthError.LockedUntil(it)))
            }

            val dek = try {
                val kek = keyManager.deriveKek(masterPassword, header.saltKdf, header.kdfParams)
                keyManager.unwrapDek(header.wrappedDekMaster, kek)
            } catch (e: KeyUnwrapException) {
                val outcome = throttle.onAuthFailure(header)
                headerStore.write(outcome.newHeader)
                return@withContext Result.failure(
                    AuthException(
                        if (outcome.lockTriggered) AuthError.LockedUntil(outcome.remainingMs)
                        else AuthError.WrongPassword((throttle.maxAttempts - (header.failedAttempts + 1)).coerceAtLeast(0)),
                    ),
                )
            }

            if (gate.availability() != BiometricGate.Availability.AVAILABLE) {
                return@withContext Result.failure(
                    AuthException(AuthError.BiometricUnavailable(gate.availability())),
                )
            }

            Result.success(EnableSetup(dek))
        }

    /** 开启第二段：用**已认证**的加密 cipher 包裹并落盘。 */
    suspend fun completeEnable(
        setup: EnableSetup,
        authenticatedCipher: Cipher,
    ): Result<Unit> = throttle.serialized {
        withContext(computeDispatcher) {
            val header = headerStore.read()
                ?: return@withContext Result.failure(AuthException(AuthError.VaultMissing))

            throttle.lockedRemainingMs(header)?.let {
                return@withContext Result.failure(AuthException(AuthError.LockedUntil(it)))
            }

            val wrapped = try {
                dekStore.wrap(setup.dek, authenticatedCipher)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    AuthException(
                        AuthError.BiometricSetupFailed("${e.javaClass.simpleName}: ${e.message}"),
                    ),
                )
            }

            headerStore.write(
                throttle.onAuthSuccess(
                    header.copy(biometricEnabled = true, wrappedDekBiometric = wrapped),
                ),
            )
            Result.success(Unit)
        }
    }

    /** 关闭：验主密码 → 清 Header 字段 + 删 Keystore 密钥。幂等。 */
    suspend fun disable(masterPassword: CharArray): Result<Unit> =
        throttle.serialized { withContext(computeDispatcher) { doDisable(masterPassword) } }

    private fun doDisable(masterPassword: CharArray): Result<Unit> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return Result.failure(AuthException(AuthError.LockedUntil(it)))
        }

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

        // 先清 Header 再删密钥：反过来的崩溃窗口会留下「开关开着、密钥没了」的永久锁死
        headerStore.write(
            throttle.onAuthSuccess(header.copy(biometricEnabled = false, wrappedDekBiometric = null)),
        )
        runCatching { dekStore.deleteKey() }
        return Result.success(Unit)
    }
}
