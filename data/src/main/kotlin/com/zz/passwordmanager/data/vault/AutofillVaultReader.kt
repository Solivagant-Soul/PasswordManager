package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.domain.autofill.AutofillCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动填充会话的读端口（v1.2.0）。
 *
 * 抽成接口只有一个理由：让 [AutofillVaultSession] 的安全语义（未解锁即拒绝、锁定即清零、
 * 越界条目拒绝）能在纯 JVM 下被单测覆盖，而不必拖起 SQLCipher。生产实现见
 * [RoomAutofillVaultReader]。
 */
interface AutofillVaultReader {

    /**
     * 列出候选（**不含密码**）。
     *
     * 走 `search("", null)` 即「全部条目」：匹配交给 `AutofillMatchPolicy`，
     * 在内存里对 5000 条做一次打分比再写一组 SQL 索引简单得多，也省掉一版迁移。
     */
    suspend fun listCandidates(dek: ByteArray): List<AutofillCandidate>

    /** 取出单条的用户名与密码明文；条目不存在时返回 null。 */
    suspend fun readFill(dek: ByteArray, entryId: String): AutofillFill?
}

/** 一次填充所需的全部明文。密码用 [CharArray] 而非 String，锁/用完即可 `fill(0)`。 */
data class AutofillFill(
    val username: String,
    val password: CharArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AutofillFill && username == other.username && password.contentEquals(other.password))

    override fun hashCode(): Int = 31 * username.hashCode() + password.contentHashCode()
}

/**
 * Room/SQLCipher 实现。
 *
 * 与 [com.zz.passwordmanager.data.entries.EntryRepositoryImpl] 的关键区别：**不经过
 * [VaultSessionManager]**。本类完全由调用方显式传入 DEK，因此自动填充会话与 UI 会话
 * 互不可见、互不影响——这正是技术方案 §9.2 要求的「自持会话」。
 */
class RoomAutofillVaultReader(
    private val dbProvider: VaultDatabaseProvider,
    private val keyManager: KeyManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutofillVaultReader {

    override suspend fun listCandidates(dek: ByteArray): List<AutofillCandidate> =
        withContext(ioDispatcher) {
            dbProvider.get(dek).entryDao().search("", null).map { row ->
                AutofillCandidate(
                    entryId = row.id,
                    title = row.title,
                    username = row.username,
                    url = row.url,
                )
            }
        }

    override suspend fun readFill(dek: ByteArray, entryId: String): AutofillFill? =
        withContext(ioDispatcher) {
            val entry = dbProvider.get(dek).entryDao().getById(entryId) ?: return@withContext null
            AutofillFill(
                username = entry.username,
                password = keyManager.decrypt(entry.passwordCipher, dek).decodeToString().toCharArray(),
            )
        }
}
