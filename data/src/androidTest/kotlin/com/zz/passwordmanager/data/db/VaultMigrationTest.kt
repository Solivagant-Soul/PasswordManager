package com.zz.passwordmanager.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 迁移验收（R9）：v1→v2 归一化列；v2→v3 收藏与条目类型；v3→v4 移除标签。 */
@RunWith(AndroidJUnit4::class)
class VaultMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
    )

    @Test
    fun migrate1To2BackfillsNormalizedColumns() {
        val dbName = "migration-test.db"

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO entries (id, title, username, passwordCipher, url, notes, " +
                    "categoryId, totpSecretCipher, createdAt, updatedAt) " +
                    "VALUES ('e1', 'AbC', 'u@x', x'00', NULL, NULL, NULL, NULL, 1, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 2, true, VaultDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT titleLower, usernameLower FROM entries WHERE id = 'e1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("abc", cursor.getString(0))
                assertEquals("u@x", cursor.getString(1))
            }
        }
    }

    /**
     * T33：v2 → v3 新增 `favorite` / `type`，既有行取默认值（未收藏 + 账号），
     * 与需求 §6.3 的默认值一致——升级后既有条目不应突然全部变成收藏或变成卡号。
     */
    @Test
    fun migrate2To3AddsFavoriteAndTypeWithDefaults() {
        val dbName = "migration-2-3.db"

        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO entries (id, title, titleLower, username, usernameLower, " +
                    "passwordCipher, url, urlLower, notes, categoryId, totpSecretCipher, " +
                    "createdAt, updatedAt) " +
                    "VALUES ('e1', 'T', 't', 'u', 'u', x'00', NULL, NULL, NULL, NULL, NULL, 1, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 3, true, VaultDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT favorite, type FROM entries WHERE id = 'e1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("既有条目应为未收藏", 0, cursor.getInt(0))
                assertEquals("既有条目应默认为账号类型", "ACCOUNT", cursor.getString(1))
            }
        }
    }

    /**
     * v3 → v4：标签功能整体移除。
     *
     * 断言两半都要证：
     * 1. **数据无损**——带标签的既有条目原样活下来（标签只是关系表，`entries` 未被碰过）；
     * 2. **标签彻底没了**——`tags` / `entry_tags` 两张表从 schema 中消失，且不可再写入。
     *    `runMigrationsAndValidate` 本身已按 4.json 校验 schema（表还在就会失败），
     *    这里再显式查一遍 sqlite_master，把「表没了」这条说成可见的断言。
     */
    @Test
    fun migrate3To4DropsTagTablesAndKeepsEntries() {
        val dbName = "migration-3-4.db"

        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO entries (id, title, titleLower, username, usernameLower, " +
                    "passwordCipher, url, urlLower, notes, categoryId, totpSecretCipher, " +
                    "favorite, type, createdAt, updatedAt) " +
                    "VALUES ('e1', 'T', 't', 'u', 'u', x'00', NULL, NULL, NULL, NULL, NULL, " +
                    "0, 'ACCOUNT', 1, 1)",
            )
            execSQL("INSERT INTO tags (name) VALUES ('work')")
            execSQL("INSERT INTO entry_tags (entryId, tagName) VALUES ('e1', 'work')")
            close()
        }

        helper.runMigrationsAndValidate(dbName, 4, true, VaultDatabase.MIGRATION_3_4).use { db ->
            // 半 1：条目活下来
            db.query("SELECT title FROM entries WHERE id = 'e1'").use { cursor ->
                assertTrue("带标签的条目必须在迁移后仍在", cursor.moveToFirst())
                assertEquals("T", cursor.getString(0))
            }

            // 半 2：两张标签表都没了
            for (table in listOf("tags", "entry_tags")) {
                db.query(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf<Any>(table),
                ).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("$table 应已从 schema 消失", 0, cursor.getInt(0))
                }
            }
        }
    }
}
