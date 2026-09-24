package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.domain.password.PasswordPolicy
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 密保找回（FR-12/T10，方案 §6.3）：3 道答案解出 DEK → 新主密码重置 → 重裹 DEK。
 *
 * - 找回与主密码共享限流计数（BR-9 同参，2026-09-23 用户拍板补 R7 缺口）；
 * - 原主密码不可取回，只能重置（BR-6）；DEK 不变 → 生物识别副本仍有效；
 * - 无半程状态（S66）：每次调用全新验证，天然满足；
 * - 答对但新密码非法 → 认证已成功不计失败，仅返回 Invalid。
 */
class ResetVault(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend operator fun invoke(
        answers: List<CharArray>,
        newPassword: CharArray,
        confirmNewPassword: CharArray,
    ): Result<Unit> = throttle.serialized {
        withContext(computeDispatcher) { reset(answers, newPassword, confirmNewPassword) }
    }

    private fun reset(
        answers: List<CharArray>,
        newPassword: CharArray,
        confirmNewPassword: CharArray,
    ): Result<Unit> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return Result.failure(AuthException(AuthError.LockedUntil(it)))
        }

        // 认证：能否用答案解出 DEK（D4 判据）
        val dek = try {
            keyManager.unwrapDek(
                header.wrappedDekRecovery,
                keyManager.deriveRecoveryKek(answers, header.saltRecovery),
            )
        } catch (e: KeyUnwrapException) {
            val outcome = throttle.onAuthFailure(header)
            headerStore.write(outcome.newHeader)
            return Result.failure(
                AuthException(
                    if (outcome.lockTriggered) AuthError.LockedUntil(outcome.remainingMs)
                    else AuthError.WrongAnswers((throttle.maxAttempts - (header.failedAttempts + 1)).coerceAtLeast(0)),
                ),
            )
        }

        // 认证已成功——新密码校验失败不计失败次数
        val issues = mutableListOf<Issue>()
        PasswordPolicy.issues(newPassword).forEach { issues += Issue("newPassword", it.name) }
        if (!newPassword.contentEquals(confirmNewPassword)) {
            issues += Issue("confirmNewPassword", "mismatch")
        }
        if (issues.isNotEmpty()) {
            return Result.failure(AuthException(AuthError.Invalid(issues)))
        }

        val newSaltKdf = ByteArray(SALT_BYTES).also(secureRandom::nextBytes) // 重置即换盐
        val newKek = keyManager.deriveKek(newPassword, newSaltKdf, header.kdfParams)
        headerStore.write(
            throttle.onAuthSuccess(
                header.copy(
                    saltKdf = newSaltKdf,
                    wrappedDekMaster = keyManager.wrapDek(dek, newKek),
                ),
            ),
        )
        return Result.success(Unit)
    }

    private companion object {
        const val SALT_BYTES = 16
    }
}
