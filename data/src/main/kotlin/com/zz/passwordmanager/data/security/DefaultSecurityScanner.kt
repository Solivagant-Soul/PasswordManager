package com.zz.passwordmanager.data.security

import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.password.PasswordStrength
import com.zz.passwordmanager.domain.security.SecurityReport
import com.zz.passwordmanager.domain.security.SecurityScanner
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 默认安全扫描实现（T42/FR-13）。
 *
 * **全程离线**——不发任何网络请求、不申请 INTERNET 权限（需求 §6.6 / C2 决策）。
 * 「已泄露」因需联网查询而被判为不做，统计卡的第四项改用「长期未更新」。
 *
 * **明文纪律**（本类的核心约束，不是可选项）：
 * 1. 重复使用检测必然要解密全部密码——这是本功能唯一无法回避的明文暴露面；
 * 2. 逐条解密、判定、**立即清零**，不留存、不缓存、不写日志；
 * 3. 能不解密就判定的部分（长期未更新只看 `updatedAt`）先做，减少解密范围；
 * 4. 返回的 [SecurityReport] 只含条目 id 与问题描述——**明文不越出本函数**。
 *
 * 重复分组用**摘要分组**而非两两比较：5000 条规模下 O(n²) 不可接受。
 */
class DefaultSecurityScanner(
    private val repository: EntryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecurityScanner {

    override suspend fun scan(): SecurityReport = withContext(ioDispatcher) {
        val entries = repository.search("", null).first()
        val total = entries.size
        if (total == 0) return@withContext SecurityReport(0, scannedAtEpochMs = now())

        val staleCutoff = now() - SecurityReport.STALE_DAYS_DAYS * SecurityReport.MILLIS_PER_DAY
        val weakIds = mutableListOf<String>()
        val staleIds = mutableListOf<String>()
        val byDigest = HashMap<String, MutableList<String>>()

        for (summary in entries) {
            val detail = repository.get(summary.id) ?: continue

            if (detail.updatedAt < staleCutoff) staleIds += detail.id

            val password = repository.revealPassword(detail.id).getOrNull() ?: continue
            if (PasswordStrength.evaluate(password) == PasswordStrength.Level.WEAK) {
                weakIds += detail.id
            }
            byDigest.getOrPut(digestOf(password)) { mutableListOf() } += detail.id
            // 明文用完即清零，不留驻
            password.fill(WIPE_CHAR)
        }

        SecurityReport(
            totalCount = total,
            weakEntryIds = weakIds,
            duplicateGroups = byDigest.values
                .filter { it.size > 1 }
                .map { it.toList() }
                .sortedByDescending { it.size },
            staleEntryIds = staleIds,
            scannedAtEpochMs = now(),
        )
    }

    /**
     * 密码摘要，作为重复分组的键。
     *
     * 逐字符取码点低 8 位构造字节——密码字符集基本是可见 ASCII，取低字节即可
     * 避免明文碰撞；真正的明文数组不参与摘要计算。
     */
    private fun digestOf(password: CharArray): String {
        val raw = ByteArray(password.size) { i -> (password[i].code and 0xFF).toByte() }
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(raw)
                .joinToString("") { "%02x".format(it) }
        } finally {
            raw.fill(0)
        }
    }

    private companion object {
        /** 清零字符。用 0.toChar() 而非字面量 '\u0000'，避免源码里嵌入真实 NUL 字节。 */
        val WIPE_CHAR = 0.toChar()
    }
}
