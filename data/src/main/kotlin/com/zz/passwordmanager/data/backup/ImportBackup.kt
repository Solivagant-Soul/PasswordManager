package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.backup.BackupVault
import com.zz.passwordmanager.domain.entries.EntryIssue
import com.zz.passwordmanager.domain.entries.EntryValidation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入加密备份（FR-7 / T27 / 需求 S2）。
 *
 * ### 全有或全无是怎么做到的
 * 分两段，**边界划在「有没有碰过密码库」上**：
 *
 * 1. **预检（本类负责，事务外）**：读文件 → 解帧 → 解密 → 选 codec → 解码 →
 *    逐条跑 `EntryValidation` + 条数上限 + 分类引用检查。**任何一步失败都在这里返回**，
 *    密码库一个字节都没被碰过。
 * 2. **提交（[BackupVault.replaceAll] 负责，事务内）**：清空 + 写入，包在 Room 事务里。
 *    预检通过后剩下的失败只可能来自 DB 自身（磁盘满、DB 损坏），由事务回滚兜底。
 *
 * 中间还特意做了一件与「校验」同等重要的事：**预检不复用明文**。
 * [inspect] 只回条目数，**不把解码结果交给 UI**——UI 要做的只是显示一个数字。
 * 这样「先解密看内容、再让用户确认」这个必然的交互需求，不会顺带在 ViewModel 里
 * 留下一份全库明文。确认后 [apply] 从原始密文**重新走一遍**预检再提交。
 * 代价是两次 Argon2 派生（约 1 秒），换来 ViewModel 里一个 `ByteArray` 都不必存明文。
 *
 * 二次校验不是多余的：文件是两次调用之间从外部拿到的引用，
 * 严格说两次读到的内容可以不同（用户改了文件、provider 有缓存）。
 */
class ImportBackup(
    private val backupVault: BackupVault,
    private val container: BackupContainer,
    private val registry: BackupCodecRegistry,
    private val keyDeriver: BackupKeyDeriver,
    private val maxEntries: Int = 5_000, // Q-07，与 EntryRepositoryImpl 同参
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 预检：只回报「这份备份里有几条」，供 UI 在确认前展示。
     *
     * 失败即导入失败：口令错、文件坏、格式不认识、版本不支持、条目不合规，一律在此返回。
     */
    suspend fun inspect(frame: ByteArray, masterPassword: CharArray): Result<BackupPreview> =
        preflight(frame, masterPassword).map { preview ->
            BackupPreview(preview.entryCount, preview.categories.size).also { preview.clear() }
        }

    /**
     * 提交：重新预检（不复用上一次的明文）→ 整库替换。
     *
     * @return 成功时为导入的条目数
     */
    suspend fun apply(frame: ByteArray, masterPassword: CharArray): Result<Int> {
        val validated = preflight(frame, masterPassword).getOrElse { return Result.failure(it) }
        val count = validated.entryCount
        return try {
            backupVault.replaceAll(validated)
                .onSuccess { validated.clear() } // 已加密入库，内存这份没有存在价值了
                .onFailure { validated.clear() }
                .map { count }
        } catch (e: Exception) {
            validated.clear()
            Result.failure(e)
        }
    }

    /**
     * 预检全流程。返回的 payload 由调用方负责 `clear()`——
     * [apply] 已确保这一点，[inspect] 干脆不把它交出去。
     */
    private suspend fun preflight(frame: ByteArray, masterPassword: CharArray): Result<BackupPayload> =
        withContext(ioDispatcher) {
            val key = keyDeriver.derive(masterPassword)
            var payload: BackupPayload? = null
            var plaintext: ByteArray? = null
            try {
                val opened = container.open(frame, key)
                plaintext = opened.plaintext
                val codec = registry[opened.codecId]
                    ?: return@withContext Result.failure(
                        BackupFormatException("unknown backup format: ${opened.codecId}"),
                    )
                payload = codec.decode(plaintext)
                val issues = validate(payload)
                if (issues.isNotEmpty()) {
                    // 预检没过，这份明文没有存在价值——必须在交出去之前清掉
                    payload.clear()
                    return@withContext Result.failure(InvalidBackupException(issues))
                }
                Result.success(payload)
            } catch (e: Exception) {
                payload?.clear()
                Result.failure(e)
            } finally {
                // 解码出来的字节已是明文，容器解出的那份同样如此，两者都不能留
                plaintext?.fill(0)
                key.fill(0)
            }
        }

    /**
     * 逐条跑**仓库自己的**那条校验（[EntryValidation]），另加两条备份特有的：
     * - 条数上限（防止一份畸形/超大备份把库撑爆，绕过 5000 的既有约束）；
     * - 分类引用完整（[BackupRecord.categoryId] 必须在本次导入的分类集合里，
     *   否则又造出一批孤儿引用——`entries.categoryId` 没有外键兜底）。
     */
    private fun validate(payload: BackupPayload): List<EntryIssue> {
        if (payload.records.size > maxEntries) {
            return listOf(EntryIssue("count", "backup holds ${payload.records.size} entries, limit is $maxEntries"))
        }
        val knownCategories = payload.categories.mapTo(mutableSetOf()) { it.id }
        val issues = mutableListOf<EntryIssue>()
        payload.records.forEach { record ->
            issues += EntryValidation.issues(record.title, record.username, record.password, record.url)
            val categoryId = record.categoryId
            if (categoryId != null && categoryId !in knownCategories) {
                issues += EntryIssue("categoryId", "unknown category reference: $categoryId")
            }
            if (record.id.isEmpty()) {
                issues += EntryIssue("id", "must not be empty")
            }
        }
        return issues
    }
}

/** 预检结果：给 UI 看的两个数字，**不含任何条目内容**。 */
data class BackupPreview(val entryCount: Int, val categoryCount: Int)

/** 备份内容不合规（条目校验失败、条数超限、分类引用断裂）。 */
class InvalidBackupException(val issues: List<EntryIssue>) :
    Exception("invalid backup: ${issues.joinToString { "${it.field}: ${it.message}" }}")
