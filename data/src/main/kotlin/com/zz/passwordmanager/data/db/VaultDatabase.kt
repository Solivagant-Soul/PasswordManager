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
 * schema v4（移除标签：分类承担归类维度）；schema 变更必须伴随 Room 迁移与迁移测试（R9）。
 */
@Database(
    entities = [EntryEntity::class, CategoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao
    abstract fun categoryDao(): CategoryDao

    companion object {

        /**
         * v3 → v4：整体移除标签功能。`tags` 与 `entry_tags` 两张表直接丢弃——
         * 标签从来只是关系表，`entries` 上没有 tags 列，故无需列手术、条目本身零影响。
         *
         * 顺序不可调换：`entry_tags` 对 `entries` 与 `tags` 都挂了外键，
         * 先删子表再删父表才不依赖 DROP TABLE 隐式 DELETE 的级联行为——
         * 反过来先删 `tags`，能否成功取决于 `PRAGMA foreign_keys` 的开关时机，不可赌。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS entry_tags")
                db.execSQL("DROP TABLE IF EXISTS tags")
            }
        }

        /**
         * v2 → v3（FR-5 收藏 / FR-15 条目类型）：新增两列并回填默认值。
         * `favorite` 与 `type` 均 NOT NULL + DEFAULT，ALTER 后既有行自动取默认值，
         * 无需 UPDATE 回填——但仍显式回填一次，保证语义不依赖 SQLite 的隐式行为。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN type TEXT NOT NULL DEFAULT 'ACCOUNT'")
                db.execSQL("UPDATE entries SET favorite = 0 WHERE favorite IS NULL")
                db.execSQL("UPDATE entries SET type = 'ACCOUNT' WHERE type IS NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_favorite ON entries(favorite)")
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
