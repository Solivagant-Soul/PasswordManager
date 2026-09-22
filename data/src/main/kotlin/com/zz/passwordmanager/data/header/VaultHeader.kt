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
    val securityQuestions: List<SecurityQuestionRecord> = emptyList(),
)
