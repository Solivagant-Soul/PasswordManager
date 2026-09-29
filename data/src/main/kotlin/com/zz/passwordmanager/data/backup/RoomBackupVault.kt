package com.zz.passwordmanager.data.backup

import androidx.room.withTransaction
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.CategoryEntity
import com.zz.passwordmanager.data.db.EntryEntity
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.backup.BackupRecord
import com.zz.passwordmanager.domain.backup.BackupVault
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [BackupVault] 的 Room 实现。
 *
 * **只做搬运，不做判断**：条目是否合规、条数是否超限、分类引用是否完整，
 * 全部由 `ImportBackup` 在进事务前判完（见该类 KDoc 的两段式说明）。
 * 混进这里会有一个具体的坏后果——「预检通过」与「实际写进去」之间又出现一段
 * 有判断的代码，两段判断一旦不一致，事务就成了最后一道而不是唯一的兜底。
 *
 * 全部方法在 [ioDispatcher] 上（开库与加解密都是阻塞式重活）。
 */
class RoomBackupVault(
    private val dbProvider: VaultDatabaseProvider,
    private val sessionManager: VaultSessionManager,
    private val keyManager: KeyManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupVault {

    override suspend fun readAll(): BackupPayload = withContext(ioDispatcher) {
        val dek = sessionManager.requireUnlocked().dek
        val db = dbProvider.get(dek)
        val records = db.entryDao().allEntities().map { it.toRecord(dek) }
        val categories = db.categoryDao().listAll().map { Category(it.id, it.name) }
        BackupPayload(records, categories)
    }

    /**
     * 整库替换：`withTransaction` 包住「清条目 + 写条目 + 清分类 + 写分类」。
     *
     * 用 Room 的 suspend 事务（而非 `runInTransaction`）——后者只接受非挂起 Runnable，
     * 装不下 suspend 的 DAO 方法（与 `CategoryRepository.delete` 同一理由）。
     * 事务保证中途任何异常都整体回滚，库回到调用前的状态。
     *
     * 字段加密在**事务外**先做完：加解密是纯 CPU 活，5000 条在事务里跑会把
     * 写锁按住不放，万一中途失败还要连加密计算一起回滚。
     */
    override suspend fun replaceAll(payload: BackupPayload): Result<Unit> = withContext(ioDispatcher) {
        val dek = sessionManager.requireUnlocked().dek
        val db = dbProvider.get(dek)
        val entities = payload.records.map { it.toEntity(dek) }
        val categories = payload.categories.map { CategoryEntity(it.id, it.name) }
        runCatching {
            db.withTransaction {
                db.entryDao().deleteAll()
                db.entryDao().insertAll(entities)
                db.categoryDao().deleteAll()
                db.categoryDao().insertAll(categories)
            }
        }.map { }
    }

    private fun EntryEntity.toRecord(dek: ByteArray): BackupRecord {
        val passwordBytes = keyManager.decrypt(passwordCipher, dek)
        val totpBytes = totpSecretCipher?.let { keyManager.decrypt(it, dek) }
        return try {
            BackupRecord(
                id = id,
                title = title,
                username = username,
                password = Utf8.decode(passwordBytes),
                url = url,
                notes = notes,
                categoryId = categoryId,
                totpSecret = totpBytes?.let { Utf8.decode(it) },
                favorite = favorite,
                type = EntryType.from(type),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        } finally {
            passwordBytes.fill(0)
            totpBytes?.fill(0)
        }
    }

    private fun BackupRecord.toEntity(dek: ByteArray): EntryEntity {
        val passwordBytes = Utf8.encode(password)
        val totpBytes = totpSecret?.let { Utf8.encode(it) }
        val titleTrimmed = title.trim()
        val usernameTrimmed = username.trim()
        return try {
            EntryEntity(
                id = id,
                title = titleTrimmed,
                titleLower = titleTrimmed.lowercase(),
                username = usernameTrimmed,
                usernameLower = usernameTrimmed.lowercase(),
                // 与 EntryRepositoryImpl 同策略：密码经 DEK 字段级二次加密（D8）
                passwordCipher = keyManager.encrypt(passwordBytes, dek),
                url = url?.trim(),
                urlLower = url?.trim()?.lowercase(),
                notes = notes,
                categoryId = categoryId,
                totpSecretCipher = totpBytes?.let { keyManager.encrypt(it, dek) },
                favorite = favorite,
                type = type.name,
                // 时间戳原样搬运：导入不是「新建」，改写时间会让用户认不出自己的条目
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        } finally {
            passwordBytes.fill(0)
            totpBytes?.fill(0)
        }
    }
}
