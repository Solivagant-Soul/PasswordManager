package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.domain.password.PasswordPolicy
import java.security.SecureRandom

/**
 * 修改主密码（T11，P-07）：验旧 → 解出 DEK → 新盐新 KEK 重裹。
 *
 * DEK 本体不变（只旋转 wrap）：因此生物识别的 Keystore DEK 副本依然有效，
 * 无需在此重裹（T09 论证，P-07 语义：改密码不改变数据密钥）。
 * 旧密码认证失败计入共享限流；新密码校验失败不计（认证已成功）。
 */
class ChangeMasterPassword(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    operator fun invoke(
        currentPassword: CharArray,
        newPassword: CharArray,
        confirmNewPassword: CharArray,
    ): Result<Unit> {
        val header = headerStore.read()
            ?: return Result.failure(AuthException(AuthError.VaultMissing))

        throttle.lockedRemainingMs(header)?.let {
            return Result.failure(AuthException(AuthError.LockedUntil(it)))
        }

        val dek = try {
            val kek = keyManager.deriveKek(currentPassword, header.saltKdf, header.kdfParams)
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

        val issues = mutableListOf<Issue>()
        PasswordPolicy.issues(newPassword).forEach { issues += Issue("newPassword", it.name) }
        if (!newPassword.contentEquals(confirmNewPassword)) {
            issues += Issue("confirmNewPassword", "mismatch")
        }
        if (issues.isNotEmpty()) {
            return Result.failure(AuthException(AuthError.Invalid(issues)))
        }

        val newSaltKdf = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
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
