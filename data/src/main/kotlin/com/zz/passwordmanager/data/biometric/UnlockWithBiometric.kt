package com.zz.passwordmanager.data.biometric

import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.data.vault.Session
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricGate
import com.zz.passwordmanager.domain.biometric.BiometricKeyInvalidatedException
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 认证前交给 :app 的挑战载荷：Header 中的 Keystore 包裹密文。 */
data class BiometricChallenge(val wrappedDek: ByteArray)

/**
 * 生物识别快捷解锁（FR-2 后半 / V1-4）。
 *
 * **必须两段式**，这是 auth-per-use（G-E）的时序决定的：
 * - [prepare] 读 Header 并做资格判定，**不进临界区**——`BiometricPrompt` 由 :app 弹出，
 *   期间若持锁，用户在提示框前用主密码解锁会被 Mutex 阻塞（本项目已知的交互坑）；
 * - [complete] 在认证成功后于临界区内解出 DEK。
 *
 * **生物识别失败不计入 `failedAttempts`**：指纹按错或用户取消不是对主密码的猜测，
 * 计数会误伤正常用户；且 unwrap 失败发生在已通过系统级强认证**之后**，
 * 说明的是数据异常而非密码错误。计数只在真正成功时归零。
 *
 * **不得绕过 BR-9 限流**：[prepare] 同样检查锁定窗口（需求 §10.2 的一致性要求）。
 */
class UnlockWithBiometric(
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val dekStore: BiometricDekStore,
    private val gate: BiometricGate,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** 第一段：资格判定，产出挑战。 */
    suspend fun prepare(): Result<BiometricChallenge> = withContext(computeDispatcher) {
        val header = headerStore.read()
            ?: return@withContext Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return@withContext Result.failure(AuthException(AuthError.LockedUntil(it)))
        }
        val wrapped = header.wrappedDekBiometric
        if (!header.biometricEnabled || wrapped == null) {
            return@withContext Result.failure(AuthException(AuthError.BiometricNotEnabled))
        }
        if (gate.availability() != BiometricGate.Availability.AVAILABLE) {
            return@withContext Result.failure(
                AuthException(AuthError.BiometricUnavailable(gate.availability())),
            )
        }
        Result.success(BiometricChallenge(wrapped))
    }

    /** 第二段：用**已认证**的 cipher 解出 DEK 并建立会话。 */
    suspend operator fun invoke(challenge: BiometricChallenge, authenticatedCipher: Cipher): Result<Session> =
        throttle.serialized { withContext(computeDispatcher) { complete(challenge, authenticatedCipher) } }

    private fun complete(challenge: BiometricChallenge, authenticatedCipher: Cipher): Result<Session> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        val dek = try {
            dekStore.unwrap(challenge.wrappedDek, authenticatedCipher)
        } catch (e: BiometricKeyInvalidatedException) {
            // 自愈：清开关 + 删 Keystore 密钥，之后只能走主密码（S14）
            disableSelfHealing(header)
            return Result.failure(AuthException(AuthError.BiometricKeyInvalidated))
        }

        headerStore.write(throttle.onAuthSuccess(header))
        return Result.success(Session(dek))
    }

    private fun disableSelfHealing(header: com.zz.passwordmanager.data.header.VaultHeader) {
        runCatching {
            headerStore.write(header.copy(biometricEnabled = false, wrappedDekBiometric = null))
            dekStore.deleteKey()
        }
    }
}
