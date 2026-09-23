package com.zz.passwordmanager.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Vault Body：整库加密的密码库（库密钥 = DEK，方案 §4.2）。
 *
 * schema v1（迁移基线，已导出入库）；schema 变更必须伴随 Room 迁移与迁移测试（R9）。
 */
@Database(
    entities = [EntryEntity::class, CategoryEntity::class, TagEntity::class, EntryTagEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun entryTagDao(): EntryTagDao

    companion object {

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
                .build()
        }
    }
}
