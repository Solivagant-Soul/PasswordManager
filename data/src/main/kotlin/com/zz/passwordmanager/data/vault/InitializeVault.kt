package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.domain.password.PasswordPolicy
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.SecurityQuestionRecord
import com.zz.passwordmanager.data.header.VaultHeader
import com.zz.passwordmanager.data.header.VaultHeaderStore
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 初始化请求（方案 §5）：答案用 CharArray，只在用例内消费、不落盘（D4）。 */
data class InitializeRequest(
    val masterPassword: CharArray,
    val confirmMasterPassword: CharArray,
    val securityQuestions: List<SecurityQuestionInput>,
) {
    data class SecurityQuestionInput(val question: String, val answer: CharArray)
}

/** 初始化错误（经 [InitializationException] 随 Result.failure 返回）。 */
sealed interface InitializationError {
    data object AlreadyInitialized : InitializationError
    data class Invalid(val issues: List<Issue>) : InitializationError
}

data class Issue(val field: String, val message: String)

class InitializationException(val error: InitializationError) : Exception(error.toString())

/**
 * 首次初始化（FR-1，方案 §6.1）：强度/一致性校验 → 双路径包裹 DEK → 原子写 Header。
 *
 * 全量校验先于任何副作用（S5：中断无脏数据）；DEK 随机生成、永不明文落盘，
 * 同时被主密码路径与密保答案路径包裹（D4 找回根基）。
 */
class InitializeVault(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val kdfParams: KdfParams = Argon2KeyManager.DEFAULT_KDF_PARAMS,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend operator fun invoke(request: InitializeRequest): Result<Unit> =
        withContext(computeDispatcher) { initialize(request) }

    private fun initialize(request: InitializeRequest): Result<Unit> {
        if (headerStore.read() != null) {
            return Result.failure(InitializationException(InitializationError.AlreadyInitialized))
        }

        val issues = validate(request)
        if (issues.isNotEmpty()) {
            return Result.failure(InitializationException(InitializationError.Invalid(issues)))
        }

        val dek = ByteArray(DEK_BYTES).also(secureRandom::nextBytes)
        val saltKdf = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val saltRecovery = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)

        val wrappedMaster = keyManager.wrapDek(
            dek,
            keyManager.deriveKek(request.masterPassword, saltKdf, kdfParams),
        )
        val wrappedRecovery = keyManager.wrapDek(
            dek,
            keyManager.deriveRecoveryKek(request.securityQuestions.map { it.answer }, saltRecovery),
        )

        headerStore.write(
            VaultHeader(
                saltKdf = saltKdf,
                kdfParams = kdfParams,
                wrappedDekMaster = wrappedMaster,
                wrappedDekRecovery = wrappedRecovery,
                saltRecovery = saltRecovery,
                securityQuestions = request.securityQuestions.mapIndexed { index, q ->
                    SecurityQuestionRecord(position = index + 1, questionText = q.question)
                },
            ),
        )
        return Result.success(Unit)
    }

    private fun validate(request: InitializeRequest): List<Issue> {
        val issues = mutableListOf<Issue>()

        PasswordPolicy.issues(request.masterPassword).forEach {
            issues += Issue(FIELD_MASTER_PASSWORD, it.name)
        }
        if (!request.masterPassword.contentEquals(request.confirmMasterPassword)) {
            issues += Issue(FIELD_CONFIRM_PASSWORD, "mismatch")
        }
        // 密保规则与 ChangeSecurityQuestions 共用（评审 G-F：防两处规则漂移）
        issues += SecurityQuestionPolicy.issues(request.securityQuestions)
        return issues
    }

    private companion object {
        const val FIELD_MASTER_PASSWORD = "masterPassword"
        const val FIELD_CONFIRM_PASSWORD = "confirmMasterPassword"
        const val DEK_BYTES = 32
        const val SALT_BYTES = 16
    }
}
