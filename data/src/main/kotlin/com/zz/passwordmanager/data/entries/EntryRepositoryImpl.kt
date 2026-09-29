package com.zz.passwordmanager.data.entries

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.EntryEntity
import com.zz.passwordmanager.data.db.EntryRow
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultState
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.entries.EntryIssue
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.entries.EntryRepositoryException
import com.zz.passwordmanager.domain.entries.EntrySummary
import com.zz.passwordmanager.domain.entries.EntryType
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * EntryRepository 实现（T14）：门禁 → 校验（需求 §6.3）→ 5000 上限（Q-07）→
 * 字段级加解密（T18：password 经 DEK）→ DAO 委派。
 * DB 经 [VaultDatabaseProvider] 惰性获取（DEK 由解锁会话给出）。
 *
 * 全部方法在 [ioDispatcher] 上执行（M2 遗留 D-1）：DEK 加解密与 SQLCipher 开库都是
 * 阻塞式重活，留在调用方（ViewModel 的 Main）会造成掉帧乃至 ANR。调度器由数据层自持，
 * 调用方无需感知（M4 autofill 等新调用方自动受益）。
 */
class EntryRepositoryImpl(
    private val dbProvider: VaultDatabaseProvider,
    private val sessionManager: VaultSessionManager,
    private val keyManager: KeyManager,
    private val maxEntries: Int = 5_000, // Q-07
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EntryRepository {

    /**
     * 响应式列表（T48/FR-18）：Room 在 `entries` 写入后自动重发查询，
     * 因此增删改条目无需显式通知，列表自然更新。
     *
     * 锁定态用 [flatMapLatest] 换成空序列而不是抛异常：锁定时 [applyLocked] 已经在
     * 清理 UI 状态，这里只需停止供数，不必把 VaultLockedException 抛给收集协程。
     */
    override fun search(query: String, categoryId: String?): Flow<List<EntrySummary>> =
        sessionManager.state.flatMapLatest { vaultState: VaultState ->
            if (vaultState !is VaultState.Unlocked) {
                flowOf(emptyList<EntrySummary>())
            } else {
                val db = dbProvider.get(vaultState.session.dek)
                db.entryDao()
                    .observe(query.trim().lowercase(), categoryId)
                    .map { list: List<EntryRow> -> list.map { it.toSummary() } }
            }
        }.flowOn(ioDispatcher)

    override suspend fun get(id: String): EntryDetail? = withContext(ioDispatcher) {
        val db = dbProvider.get(sessionManager.requireUnlocked().dek)
        db.entryDao().getById(id)?.toDetail()
    }

    override suspend fun create(input: EntryInput): Result<String> = withContext(ioDispatcher) {
        val dek = sessionManager.requireUnlocked().dek
        val db = dbProvider.get(dek)
        val entryDao = db.entryDao()

        val issues = validate(input)
        if (issues.isNotEmpty()) {
            return@withContext Result.failure(
                EntryRepositoryException(EntryRepositoryException.Reason.INVALID, issues),
            )
        }
        if (entryDao.count() >= maxEntries) {
            return@withContext Result.failure(
                EntryRepositoryException(EntryRepositoryException.Reason.LIMIT_REACHED),
            )
        }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        entryDao.upsert(
            EntryEntity(
                id = id,
                title = input.title.trim(),
                titleLower = input.title.trim().lowercase(),
                username = input.username.trim(),
                usernameLower = input.username.trim().lowercase(),
                passwordCipher = keyManager.encrypt(input.password.concatToString().encodeToByteArray(), dek),
                url = input.url?.trim(),
                urlLower = input.url?.trim()?.lowercase(),
                notes = input.notes,
                categoryId = input.categoryId,
                favorite = input.favorite,
                type = input.type.name,
                // TOTP 密钥与密码同等加密（NFR-S7）；一期 UI 不传，取值恒 null
                totpSecretCipher = input.totpSecret?.let {
                    keyManager.encrypt(it.concatToString().encodeToByteArray(), dek)
                },
                createdAt = now,
                updatedAt = now,
            ),
        )
        Result.success(id)
    }

    override suspend fun update(id: String, input: EntryInput): Result<Unit> = withContext(ioDispatcher) {
        val dek = sessionManager.requireUnlocked().dek
        val db = dbProvider.get(dek)
        val entryDao = db.entryDao()

        val existing = entryDao.getById(id)
            ?: return@withContext Result.failure(
                EntryRepositoryException(EntryRepositoryException.Reason.NOT_FOUND),
            )
        val issues = validate(input)
        if (issues.isNotEmpty()) {
            return@withContext Result.failure(
                EntryRepositoryException(EntryRepositoryException.Reason.INVALID, issues),
            )
        }

        entryDao.upsert(
            existing.copy(
                title = input.title.trim(),
                titleLower = input.title.trim().lowercase(),
                username = input.username.trim(),
                usernameLower = input.username.trim().lowercase(),
                passwordCipher = keyManager.encrypt(input.password.concatToString().encodeToByteArray(), dek),
                url = input.url?.trim(),
                urlLower = input.url?.trim()?.lowercase(),
                notes = input.notes,
                categoryId = input.categoryId,
                favorite = input.favorite,
                type = input.type.name,
                // null = 未提供，保留库中原有 TOTP 密钥（清除语义属二期 T21）
                totpSecretCipher = input.totpSecret
                    ?.let { keyManager.encrypt(it.concatToString().encodeToByteArray(), dek) }
                    ?: existing.totpSecretCipher,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Result.success(Unit)
    }

    override suspend fun setFavorite(id: String, favorite: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            val db = dbProvider.get(sessionManager.requireUnlocked().dek)
            if (db.entryDao().getById(id) == null) {
                return@withContext Result.failure(
                    EntryRepositoryException(EntryRepositoryException.Reason.NOT_FOUND),
                )
            }
            db.entryDao().setFavorite(id, favorite)
            Result.success(Unit)
        }

    override suspend fun delete(id: String): Result<Unit> = withContext(ioDispatcher) {
        val dek = sessionManager.requireUnlocked().dek
        dbProvider.get(dek).entryDao().deleteById(id)
        Result.success(Unit)
    }

    override suspend fun revealPassword(id: String): Result<CharArray> = withContext(ioDispatcher) {
        val dek = sessionManager.requireUnlocked().dek
        val entry = dbProvider.get(dek).entryDao().getById(id)
            ?: return@withContext Result.failure(
                EntryRepositoryException(EntryRepositoryException.Reason.NOT_FOUND),
            )
        val plain = keyManager.decrypt(entry.passwordCipher, dek)
        Result.success(plain.decodeToString().toCharArray()) // plain 字节由 GC 处理（T05 已知局限）
    }

    private fun validate(input: EntryInput): List<EntryIssue> {
        val issues = mutableListOf<EntryIssue>()
        val title = input.title.trim()
        if (title.isEmpty() || title.length > TITLE_MAX) {
            issues += EntryIssue("title", "length must be 1..$TITLE_MAX")
        }
        // v1.1.2：用户名与名称、密码同为必填（此前允许为空）。校验放在仓库层而非
        // 只靠编辑页——绕过 UI 的调用方（导入链路、未来自动化）同样要吃到这条规则。
        // message 里带上字段名，是为了让 :app 的 invalidMessage 能把它映射成
        // 「用户名 / 账号必填」而不是落到兜底文案。
        if (input.username.isBlank()) {
            issues += EntryIssue("username", "username must not be empty")
        }
        if (input.password.isEmpty()) {
            issues += EntryIssue("password", "must not be empty")
        }
        val url = input.url?.trim().orEmpty()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            issues += EntryIssue("url", "must start with http:// or https://")
        }
        return issues
    }

    private fun com.zz.passwordmanager.data.db.EntryRow.toSummary() =
        EntrySummary(id, title, username, url, categoryId, favorite, EntryType.from(type))

    private fun EntryEntity.toDetail() = EntryDetail(
        id = id, title = title, username = username, url = url, notes = notes,
        categoryId = categoryId, totpEnabled = totpSecretCipher != null,
        favorite = favorite, type = EntryType.from(type),
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private companion object {
        const val TITLE_MAX = 100
    }
}
