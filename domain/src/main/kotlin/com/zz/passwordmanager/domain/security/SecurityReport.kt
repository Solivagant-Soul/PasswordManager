package com.zz.passwordmanager.domain.security

/**
 * 安全扫描结果（FR-13）。
 *
 * **结果只携带条目 id 与问题描述，绝不携带任何密码明文**——扫描必然要解密，
 * 但明文的生命周期必须止于扫描函数内部。这是本模块的硬约束。
 */
data class SecurityReport(
    val totalCount: Int,
    /** 弱密码条目 id。 */
    val weakEntryIds: List<String> = emptyList(),
    /** 重复使用分组：同一组内共用一个密码的条目 id 集合。 */
    val duplicateGroups: List<List<String>> = emptyList(),
    /** 长期未更新（超过 [STALE_DAYS_DAYS] 天）的条目 id。 */
    val staleEntryIds: List<String> = emptyList(),
    val scannedAtEpochMs: Long = 0L,
) {
    val weakCount: Int get() = weakEntryIds.size
    val staleCount: Int get() = staleEntryIds.size

    /** 处于「需处理」状态的条目总数（重复组按组计一次）。 */
    val actionableCount: Int
        get() = weakEntryIds.size + duplicateGroups.size + staleEntryIds.size

    companion object {
        /** 长期未更新阈值（需求 §6.6）。 */
        const val STALE_DAYS_DAYS = 180
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/** 安全扫描端口。实现必须在解锁态内完成，并清零中间明文。 */
interface SecurityScanner {
    suspend fun scan(): SecurityReport
}
