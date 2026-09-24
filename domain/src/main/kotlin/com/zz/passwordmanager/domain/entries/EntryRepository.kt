package com.zz.passwordmanager.domain.entries

import kotlinx.coroutines.flow.Flow

/** 条目摘要：列表/搜索投影，不含密码（明文或密文）。 */
data class EntrySummary(
    val id: String,
    val title: String,
    val username: String,
    val url: String?,
    val categoryId: String?,
)

/** 详情：密码明文经 [EntryRepository.revealPassword] 单独获取（掩码/显隐，FR-9）。 */
data class EntryDetail(
    val id: String,
    val title: String,
    val username: String,
    val url: String?,
    val notes: String?,
    val categoryId: String?,
    val tags: List<String>,
    val totpEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 新建/编辑输入：密码 CharArray（方案 §5 敏感参数原则）。 */
data class EntryInput(
    val title: String,
    val username: String,
    val password: CharArray,
    val url: String?,
    val notes: String?,
    val categoryId: String?,
    val tags: List<String>,
)

interface EntryRepository {

    /** 搜索/列表（返回摘要；锁定态由 UI 状态机挡在门外，此方法不含敏感信息）。 */
    fun search(query: String, categoryId: String?, tag: String?): Flow<List<EntrySummary>>

    suspend fun get(id: String): EntryDetail?

    /** @throws VaultLockedException 锁定态（BR-7）；实现随 Result 返回 LimitReached/Invalid */
    suspend fun create(input: EntryInput): Result<String>

    suspend fun update(id: String, input: EntryInput): Result<Unit>

    /** 永久删除（UI 负责二次确认，FR-3）；条目关联标签随外键级联清理。 */
    suspend fun delete(id: String): Result<Unit>

    /** 明文密码（掩码显隐/复制，FR-9）。仅解锁态；@throws VaultLockedException */
    suspend fun revealPassword(id: String): Result<CharArray>
}

/** 仓库层异常（随 Result.failure 返回）。 */
class EntryRepositoryException(val reason: Reason, val issues: List<EntryIssue> = emptyList()) :
    Exception(reason.name) {

    enum class Reason { LIMIT_REACHED, NOT_FOUND, INVALID }
}

data class EntryIssue(val field: String, val message: String)
