package com.zz.passwordmanager.data.header

import kotlinx.serialization.Serializable

/** KDF 参数（Argon2id 三元组；具体值 T05 基准校准，D3）。 */
@Serializable
data class KdfParams(
    val iterations: Int,
    val memoryKib: Int,
    val parallelism: Int,
)

/**
 * 密保问题（方案 §4.1）：只存「问题」文本供找回时展示；
 * 答案不落盘（D4）——能否用答案解出 DEK 即「答对/答错」的判据。
 */
@Serializable
data class SecurityQuestionRecord(
    val position: Int,
    val questionText: String,
)

/**
 * Vault Header：解锁引导元数据（方案 §4.1，B1 修正的核心）。
 *
 * 存储不依赖 DEK——开库前必须可读：盐/KDF 参数/被包裹的 DEK/失败计数/密保问题。
 * [failedAttempts]/[lockUntilEpochMs] 受 headerMac（HMAC）保护，防篡改绕过 BR-9 限流。
 */
@Serializable
data class VaultHeader(
    val headerVersion: Int = 1,
    val saltKdf: ByteArray,
    val kdfParams: KdfParams,
    val wrappedDekMaster: ByteArray,
    val wrappedDekRecovery: ByteArray,
    val saltRecovery: ByteArray,
    val failedAttempts: Int = 0,
    val lockUntilEpochMs: Long = 0L,
    val biometricEnabled: Boolean = false,
    /**
     * Keystore 包裹的 DEK 快捷副本（nonce‖密文+tag），null = 无快捷副本。
     *
     * 存进 Header 而非独立文件：开关位与被包裹密文是同一逻辑事实的两面，
     * 放一起才能靠已有的原子写（临时文件 + ATOMIC_MOVE）避免出现
     * 「开关开了但密文没写成功」的中间态；且受 headerMac 覆盖，篡改可检测
     * （独立文件需要另做完整性保护）。`ChangeMasterPassword`/`ResetVault` 的
     * `header.copy(...)` 会自动保留本字段——DEK 本体不旋转，快捷副本继续有效。
     */
    val wrappedDekBiometric: ByteArray? = null,
    val securityQuestions: List<SecurityQuestionRecord> = emptyList(),
)
