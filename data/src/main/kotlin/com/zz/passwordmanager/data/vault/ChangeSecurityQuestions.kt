package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.crypto.KeyUnwrapException
import com.zz.passwordmanager.data.header.SecurityQuestionRecord
import com.zz.passwordmanager.data.header.VaultHeaderStore
import java.security.SecureRandom

/**
 * 修改密保（T10/P-10）：验当前主密码 → 新问题+新答案重派生恢复密钥 → 重裹 DEK 副本。
 *
 * DEK 本体不变 → 主密码 wrap 与生物识别副本均无需更新；
 * 恢复盐同时更换（saltRecovery），旧答案组合立即失效。
 */
class ChangeSecurityQuestions(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val throttle: AuthThrottle,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    operator fun invoke(
        questions: List<InitializeRequest.SecurityQuestionInput>,
        currentPassword: CharArray,
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
        if (questions.size != 3) {
            issues += Issue("securityQuestions", "expected 3 questions")
        } else {
            questions.forEachIndexed { index, q ->
                val question = q.question.trim()
                val answer = q.answer.concatToString().trim()
                if (question.isEmpty() || question.length > 100) {
                    issues += Issue("securityQuestions", "question ${index + 1}: invalid length")
                }
                if (answer.isEmpty() || answer.length > 60) {
                    issues += Issue("securityQuestions", "answer ${index + 1}: invalid length")
                }
            }
        }
        if (issues.isNotEmpty()) {
            return Result.failure(AuthException(AuthError.Invalid(issues)))
        }

        val newSaltRecovery = ByteArray(SALT_RECOVERY_BYTES).also(secureRandom::nextBytes)
        val wrappedRecovery = keyManager.wrapDek(
            dek,
            keyManager.deriveRecoveryKek(questions.map { it.answer }, newSaltRecovery),
        )
        headerStore.write(
            throttle.onAuthSuccess(
                header.copy(
                    saltRecovery = newSaltRecovery,
                    wrappedDekRecovery = wrappedRecovery,
                    securityQuestions = questions.mapIndexed { index, q ->
                        SecurityQuestionRecord(position = index + 1, questionText = q.question)
                    },
                ),
            ),
        )
        return Result.success(Unit)
    }

    private companion object {
        const val SALT_RECOVERY_BYTES = 16
    }
}
