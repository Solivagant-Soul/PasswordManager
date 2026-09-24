package com.zz.passwordmanager.data.entries

import androidx.room.withTransaction
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultState
import com.zz.passwordmanager.domain.entries.Category
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分类仓库（T34/FR-5）。
 *
 * **分类是单选的筛选维度，标签是多值的归类维度**——需求 §13.0 C6 的收口结论。
 *
 * 关键取舍：**不加外键**。`entries.categoryId` 与 `categories` 之间没有外键约束，
 * 删除分类不会自动回退——所以 [delete] 必须在**同一事务**内先把
 * `entries.categoryId` 置 null 再删分类行。理由：删除分类是低频操作，显式事务比
 * 引入 `ON DELETE SET NULL` 更易推理，也不必为历史脏数据写迁移清洗逻辑。
 */
class CategoryRepository(
    private val dbProvider: VaultDatabaseProvider,
    private val sessionManager: VaultSessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 列出全部分类（按名称排序）。 */
    suspend fun list(): List<Category> = withContext(ioDispatcher) {
        requireUnlocked().categoryDao().listAll()
            .map { Category(it.id, it.name) }
            .sortedBy { it.name }
    }

    /** 新建或重命名分类（id 由调用方给出，便于编辑页复用同一路径）。 */
    suspend fun upsert(id: String, name: String): Result<Category> = withContext(ioDispatcher) {
        val trimmed = name.trim()
        val issues = validate(trimmed)
        if (issues.isNotEmpty()) {
            return@withContext Result.failure(CategoryException(issues))
        }
        val db = requireUnlocked()
        // 撞名检查必须排除自己——否则**重命名成原名**会被误判为重名
        val duplicate = db.categoryDao().listAll()
            .any { it.name == trimmed && it.id != id }
        if (duplicate) {
            return@withContext Result.failure(
                CategoryException(listOf(CategoryIssue("name", "已存在同名分类"))),
            )
        }
        db.categoryDao().upsert(com.zz.passwordmanager.data.db.CategoryEntity(id, trimmed))
        Result.success(Category(id, trimmed))
    }

    /**
     * 删除分类：其下条目**保留**，仅回退为「未分类」（需求 §6.5）。
     * 同一事务内先清引用再删分类，避免出现指向不存在分类的孤儿引用。
     */
    suspend fun delete(id: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val db = requireUnlocked()
            // 用 Room 的 suspend 事务（`withTransaction`）而非 `runInTransaction`——
            // 后者只接受非挂起 Runnable，装不下 suspend 的 DAO 方法
            db.withTransaction {
                db.entryDao().clearCategory(id)
                db.categoryDao().deleteById(id)
            }
        }
    }

    private fun validate(name: String): List<CategoryIssue> = buildList {
        if (name.isEmpty() || name.isBlank()) add(CategoryIssue("name", "分类名不能为空"))
        if (name.length > NAME_MAX) add(CategoryIssue("name", "分类名不超过 $NAME_MAX 字"))
    }

    private fun requireUnlocked() =
        (sessionManager.state.value as? VaultState.Unlocked)?.let {
            dbProvider.get(it.session.dek)
        } ?: throw com.zz.passwordmanager.data.vault.VaultLockedException()

    private companion object {
        const val NAME_MAX = 30
    }
}

class CategoryException(val issues: List<CategoryIssue>) : Exception(issues.toString())

data class CategoryIssue(val field: String, val message: String)
