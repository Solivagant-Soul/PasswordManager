package com.zz.passwordmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** T13 DAO：搜索/过滤/计数/标签深化（M2）；T03 的基础读写保留。 */
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

    /**
     * 列表/搜索（投影不含密文；M4 大小写不敏感经归一化列实现）。
     * 包含式 LIKE 在 5000 条规模为有限全表扫描（索引服务等值/前缀与排序）。
     */
    @Query(
        "SELECT id, title, username, url, categoryId FROM entries " +
            "WHERE (:query = '' OR titleLower LIKE '%' || :query || '%' " +
            "OR usernameLower LIKE '%' || :query || '%' OR urlLower LIKE '%' || :query || '%') " +
            "AND (:categoryId IS NULL OR categoryId = :categoryId) " +
            "ORDER BY titleLower",
    )
    suspend fun search(query: String, categoryId: String?): List<EntryRow>

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun countForLimit(): Int

    /** 标签过滤（JOIN entry_tags）+ 可选分类/搜索组合。 */
    @Query(
        "SELECT e.id, e.title, e.username, e.url, e.categoryId FROM entries e " +
            "JOIN entry_tags t ON e.id = t.entryId " +
            "WHERE t.tagName = :tag " +
            "AND (:categoryId IS NULL OR e.categoryId = :categoryId) " +
            "AND (:query = '' OR e.titleLower LIKE '%' || :query || '%' " +
            "OR e.usernameLower LIKE '%' || :query || '%' OR e.urlLower LIKE '%' || :query || '%') " +
            "ORDER BY e.titleLower",
    )
    suspend fun searchByTag(tag: String, categoryId: String?, query: String): List<EntryRow>
}

/** 列表/搜索投影（不含密码密文——列表场景无需解密）。 */
data class EntryRow(
    val id: String,
    val title: String,
    val username: String,
    val url: String?,
    val categoryId: String?,
)

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("SELECT * FROM categories")
    suspend fun listAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM entries WHERE categoryId = :categoryId")
    suspend fun entryCountFor(categoryId: String): Int
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity)

    @Query("SELECT * FROM tags")
    suspend fun listAll(): List<TagEntity>
}

@Dao
interface EntryTagDao {

    @Insert
    suspend fun insertAll(links: List<EntryTagEntity>)

    @Query("SELECT tagName FROM entry_tags WHERE entryId = :entryId")
    suspend fun tagsFor(entryId: String): List<String>

    @Query("DELETE FROM entry_tags WHERE entryId = :entryId")
    suspend fun deleteFor(entryId: String)
}
