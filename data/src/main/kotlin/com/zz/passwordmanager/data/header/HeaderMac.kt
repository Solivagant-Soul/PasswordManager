package com.zz.passwordmanager.data.header

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Header 完整性校验（R11）：HMAC-SHA256。
 *
 * 覆盖 Header 的全部业务字段——攻击者篡改失败计数/锁定时钟即校验失败，
 * 调用方必须拒绝（防绕过 BR-9「5 次锁 1 分钟」限流）。
 * MAC 密钥由调用方注入；其生成与 Keystore 托管属 T05/T29。
 */
class HeaderMac(macKey: ByteArray) {

    private val mac = Mac.getInstance(ALGORITHM).apply {
        init(SecretKeySpec(macKey, ALGORITHM))
    }

    fun compute(data: ByteArray): ByteArray = mac.doFinal(data)

    /** 常量时间比较，避免时序侧信道。 */
    fun verify(data: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(compute(data), expected)

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
