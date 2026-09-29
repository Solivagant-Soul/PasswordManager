package com.zz.passwordmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** T13 DAO：搜索/过滤/计数（M2）；T03 的基础读写保留。 */
@Dao
interface EntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EntryEntity)

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getById(id: String): EntryEntity?

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun count(): Int

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 只改收藏位（FR-5）：不解密密码，不动其他列。 */
    @Query("UPDATE entries SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /**
     * 删除分类前先清引用（T34）。更新的是 `entries` 表，故归 EntryDao。
     *
     * `entries.categoryId` **没有外键**，不会自动回退——必须显式置 null，
     * 否则删除分类后会留下指向不存在分类的孤儿引用，按分类筛选时行为不可预期。
     */
    @Query("UPDATE entries SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: String)

    /**
     * 列表/搜索（投影不含密文；M4 大小写不敏感经归一化列实现）。
     * 包含式 LIKE 在 5000 条规模为有限全表扫描（索引服务等值/前缀与排序）。
     */
    @Query(
        "SELECT id, title, username, url, categoryId, favorite, type FROM entries " +
            "WHERE (:query = '' OR titleLower LIKE '%' || :query || '%' " +
            "OR usernameLower LIKE '%' || :query || '%' OR urlLower LIKE '%' || :query || '%') " +
            "AND (:categoryId IS NULL OR categoryId = :categoryId) " +
            "ORDER BY titleLower",
    )
    suspend fun search(query: String, categoryId: String?): List<EntryRow>

    /**
     * 列表/搜索的**响应式**版本（T48/FR-18）。
     *
     * Room 会在 `entries` 表发生任何写入后自动重新发射——因此新增/编辑/删除条目
     * 无需显式通知，列表自然更新。此前 VM 用 `search(...).first()` 一次性取值
     * 再手动 `refresh()`，跨页面变更不会自动反映。
     */
    @Query(
        "SELECT id, title, username, url, categoryId, favorite, type FROM entries " +
            "WHERE (:query = '' OR titleLower LIKE '%' || :query || '%' " +
            "OR usernameLower LIKE '%' || :query || '%' OR urlLower LIKE '%' || :query || '%') " +
            "AND (:categoryId IS NULL OR categoryId = :categoryId) " +
            "ORDER BY titleLower",
    )
    fun observe(query: String, categoryId: String?): Flow<List<EntryRow>>

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun countForLimit(): Int

    // ---- 备份导入/导出（FR-7 / T26–T27） ----

    /**
     * 全量读出**含密文**的实体。这是 `entries` 表唯一一处会拿到 `passwordCipher` 的查询，
     * 调用方（`RoomBackupVault`）拿到后立刻用 DEK 解密。列表/搜索一律走
     * [EntryRow] 投影，不碰这一列。
     */
    @Query("SELECT * FROM entries")
    suspend fun allEntities(): List<EntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<EntryEntity>)

    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}

/** 列表/搜索投影（不含密码密文——列表场景无需解密）。 */
data class EntryRow(
    val id: String,
    val title: String,
    val username: String,
    val url: String?,
    val categoryId: String?,
    val favorite: Boolean = false,
    val type: String = "ACCOUNT",
)

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("SELECT * FROM categories")
    suspend fun listAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM entries WHERE categoryId = :categoryId")
    suspend fun entryCountFor(categoryId: String): Int

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    // ---- 备份导入（FR-7） ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    /**
     * 整库替换时清空分类。与条目同在一个 Room 事务里（`RoomBackupVault.replaceAll`），
     * 因此不存在「条目已换、分类还没换」的中间态。
     */
    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}
