package com.zz.passwordmanager.data.entries

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.EntryEntity
import com.zz.passwordmanager.data.db.EntryTagEntity
import com.zz.passwordmanager.data.db.TagEntity
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.entries.EntryIssue
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.entries.EntryRepositoryException
import com.zz.passwordmanager.domain.entries.EntrySummary
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * EntryRepository 实现（T14）：门禁 → 校验（需求 §6.3）→ 5000 上限（Q-07）→
 * 字段级加解密（T18：password 经 DEK）→ DAO 委派。
 * DB 经 [VaultDatabaseProvider] 惰性获取（DEK 由解锁会话给出）。
 */
class EntryRepositoryImpl(
    private val dbProvider: VaultDatabaseProvider,
    private val sessionManager: VaultSessionManager,
    private val keyManager: KeyManager,
    private val maxEntries: Int = 5_000, // Q-07
) : EntryRepository {

    override fun search(query: String, categoryId: String?, tag: String?): Flow<List<EntrySummary>> =
        flow {
            val dek = sessionManager.requireUnlocked().dek // 摘要不含密码，但锁定态整体挡在门外（BR-7）
            val db = dbProvider.get(dek)
            val normalized = query.trim().lowercase()
            val rows = if (tag != null) {
                db.entryDao().searchByTag(tag.trim().lowercase(), categoryId, normalized)
            } else {
                db.entryDao().search(normalized, categoryId)
            }
            emit(rows.map { it.toSummary() })
        }

    override suspend fun get(id: String): EntryDetail? {
        val db = dbProvider.get(sessionManager.requireUnlocked().dek)
        return db.entryDao().getById(id)?.let { it.toDetail(db.entryTagDao().tagsFor(id)) }
    }

    override suspend fun create(input: EntryInput): Result<String> {
        val dek = sessionManager.requireUnlocked().dek
        val db = dbProvider.get(dek)
        val entryDao = db.entryDao()
        val tagDao = db.tagDao()
        val entryTagDao = db.entryTagDao()

        val issues = validate(input)
        if (issues.isNotEmpty()) {
            return Result.failure(EntryRepositoryException(EntryRepositoryException.Reason.INVALID, issues))
        }
        if (entryDao.count() >= maxEntries) {
            return Result.failure(EntryRepositoryException(EntryRepositoryException.Reason.LIMIT_REACHED))
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
                totpSecretCipher = null, // TOTP 录入属 T21
                createdAt = now,
                updatedAt = now,
            ),
        )
        saveTags(tagDao, entryTagDao, id, input.tags)
        return Result.success(id)
    }

    override suspend fun update(id: String, input: EntryInput): Result<Unit> {
        val dek = sessionManager.requireUnlocked().dek
        val db = dbProvider.get(dek)
        val entryDao = db.entryDao()
        val tagDao = db.tagDao()
        val entryTagDao = db.entryTagDao()

        val existing = entryDao.getById(id)
            ?: return Result.failure(EntryRepositoryException(EntryRepositoryException.Reason.NOT_FOUND))
        val issues = validate(input)
        if (issues.isNotEmpty()) {
            return Result.failure(EntryRepositoryException(EntryRepositoryException.Reason.INVALID, issues))
        }

        entryTagDao.deleteFor(id)
        saveTags(tagDao, entryTagDao, id, input.tags)
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
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return Result.success(Unit)
    }

    override suspend fun delete(id: String): Result<Unit> {
        val dek = sessionManager.requireUnlocked().dek
        dbProvider.get(dek).entryDao().deleteById(id) // entry_tags 经外键 CASCADE 清理
        return Result.success(Unit)
    }

    override suspend fun revealPassword(id: String): Result<CharArray> {
        val dek = sessionManager.requireUnlocked().dek
        val entry = dbProvider.get(dek).entryDao().getById(id)
            ?: return Result.failure(EntryRepositoryException(EntryRepositoryException.Reason.NOT_FOUND))
        val plain = keyManager.decrypt(entry.passwordCipher, dek)
        return Result.success(plain.decodeToString().toCharArray()) // plain 字节由 GC 处理（T05 已知局限）
    }

    private suspend fun saveTags(
        tagDao: com.zz.passwordmanager.data.db.TagDao,
        entryTagDao: com.zz.passwordmanager.data.db.EntryTagDao,
        entryId: String,
        tags: List<String>,
    ) {
        val distinct = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(TAGS_MAX)
        if (distinct.isNotEmpty()) {
            distinct.forEach { tagDao.insert(TagEntity(name = it)) }
            entryTagDao.insertAll(distinct.map { EntryTagEntity(entryId = entryId, tagName = it) })
        }
    }

    private fun validate(input: EntryInput): List<EntryIssue> {
        val issues = mutableListOf<EntryIssue>()
        val title = input.title.trim()
        if (title.isEmpty() || title.length > TITLE_MAX) {
            issues += EntryIssue("title", "length must be 1..$TITLE_MAX")
        }
        if (input.password.isEmpty()) {
            issues += EntryIssue("password", "must not be empty")
        }
        val url = input.url?.trim().orEmpty()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            issues += EntryIssue("url", "must start with http:// or https://")
        }
        if (input.tags.size > TAGS_MAX) {
            issues += EntryIssue("tags", "at most $TAGS_MAX tags")
        }
        return issues
    }

    private fun com.zz.passwordmanager.data.db.EntryRow.toSummary() =
        EntrySummary(id, title, username, url, categoryId)

    private fun EntryEntity.toDetail(tags: List<String>) = EntryDetail(
        id = id, title = title, username = username, url = url, notes = notes,
        categoryId = categoryId, tags = tags, totpEnabled = totpSecretCipher != null,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private companion object {
        const val TITLE_MAX = 100
        const val TAGS_MAX = 20
    }
}
