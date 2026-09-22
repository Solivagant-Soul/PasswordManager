package com.zz.passwordmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** T03 最小 DAO：仅支撑「Body 可加密创建/打开」验收与上层基础读写；搜索/过滤在 T13/T24 深化。 */
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
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("SELECT * FROM categories")
    suspend fun listAll(): List<CategoryEntity>
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
}
