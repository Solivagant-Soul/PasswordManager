package com.zz.passwordmanager.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 迁移验收（R9）：v1→v2 归一化列；v2→v3 收藏与条目类型。 */
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
}
