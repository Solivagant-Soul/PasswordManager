package com.zz.passwordmanager.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Vault Body：整库加密的密码库（库密钥 = DEK，方案 §4.2）。
 *
 * schema v2（T13：归一化搜索列 + 索引）；schema 变更必须伴随 Room 迁移与迁移测试（R9）。
 */
@Database(
    entities = [EntryEntity::class, CategoryEntity::class, TagEntity::class, EntryTagEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun entryTagDao(): EntryTagDao

    companion object {

        /**
         * v1 → v2（T13）：entries 增加归一化搜索列并回填 lower()（ASCII 降级语义，
         * 对无大小写概念的文字无影响），再建索引。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN titleLower TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN usernameLower TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN urlLower TEXT")
                db.execSQL(
                    "UPDATE entries SET titleLower = lower(title), " +
                        "usernameLower = lower(username), urlLower = lower(url)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_titleLower ON entries(titleLower)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_usernameLower ON entries(usernameLower)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_urlLower ON entries(urlLower)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_categoryId ON entries(categoryId)")
            }
        }

        /**
         * 创建加密密码库。
         *
         * @param dek 数据加密密钥原始字节——即 SQLCipher 库密钥（32 字节，AES-256 语境）。
         *   T03 由调用方注入；T07 起由解锁会话（主密码解出的 DEK）提供。
         */
        fun create(context: Context, dek: ByteArray, databaseName: String = "vault.db"): VaultDatabase {
            // SQLCipher native 库必须在使用前加载（幂等）；缺失会 UnsatisfiedLinkError
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, VaultDatabase::class.java, databaseName)
                .openHelperFactory(SupportOpenHelperFactory(dek))
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
