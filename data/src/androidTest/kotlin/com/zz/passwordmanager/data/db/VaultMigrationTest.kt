package com.zz.passwordmanager.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** T13 迁移验收（R9）：v1→v2 归一化列建立并回填 lower()。 */
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
}
