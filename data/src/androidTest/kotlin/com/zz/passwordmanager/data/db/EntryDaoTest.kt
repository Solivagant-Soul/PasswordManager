package com.zz.passwordmanager.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T13 DAO 验收（真 SQLite，in-memory；DAO 逻辑与加密无关）：
 * 归一化搜索、分类过滤、计数、收藏单列更新。
 */
@RunWith(AndroidJUnit4::class)
class EntryDaoTest {

    private lateinit var db: VaultDatabase
    private val entryDao get() = db.entryDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(id: String, title: String, username: String = "u", categoryId: String? = null) =
        EntryEntity(
            id = id, title = title, titleLower = title.lowercase(),
            username = username, usernameLower = username.lowercase(),
            passwordCipher = ByteArray(16), url = null, urlLower = null, notes = null,
            categoryId = categoryId, totpSecretCipher = null, createdAt = 1L, updatedAt = 1L,
        )

    @Test
    fun searchMatchesLowerCasedColumnsCaseInsensitively() = runBlocking {
        entryDao.upsert(entry("e1", "GitHub", "OctoCat"))

        assertEquals("e1", entryDao.search("github", null).single().id)
        assertEquals("e1", entryDao.search("GITHUB", null).single().id)
        assertEquals("e1", entryDao.search("octocat", null).single().id)
        assertTrue(entryDao.search("nope", null).isEmpty())
    }

    @Test
    fun searchFiltersByCategory() = runBlocking {
        entryDao.upsert(entry("e1", "A", categoryId = "c1"))
        entryDao.upsert(entry("e2", "B", categoryId = "c2"))

        assertEquals(listOf("e1"), entryDao.search("", "c1").map { it.id })
    }

    @Test
    fun countReflectsRows() = runBlocking {
        entryDao.upsert(entry("e1", "A"))
        entryDao.upsert(entry("e2", "B"))

        assertEquals(2, entryDao.count())
    }

    /**
     * T48/FR-18 回归：写入后 `observe` **自动**重发，无需显式刷新。
     * 这是「列表实时更新」的数据层保证——上层的 Flow 订阅靠它成立。
     */
    @Test
    fun observeEmitsAgainAfterWrite() = runBlocking {
        val emissions = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) {
            entryDao.observe("", null).collect { emissions += it.size }
        }
        // 订阅已在 Unconfined 上立即开始，等首值到达
        while (emissions.isEmpty()) yield()
        assertEquals(0, emissions.last())

        entryDao.upsert(entry("e1", "A"))

        // Room 在事务提交后重发，无需任何显式通知
        while (emissions.size < 2) yield()
        assertEquals(1, emissions.last())

        job.cancel()
    }

    /**
     * T34 回归：删除分类前必须**先清引用**。
     *
     * `entries.categoryId` 没有外键，不会自动回退——若直接删分类行，
     * 就会留下指向不存在分类的孤儿引用，列表按分类筛选时行为不可预期。
     * 本用例守住 `clearCategory` 这个显式回退。
     */
    @Test
    fun clearCategoryResetsEntriesToUncategorized() = runBlocking {
        entryDao.upsert(entry("e1", "A", categoryId = "c1"))
        entryDao.upsert(entry("e2", "B", categoryId = "c1"))

        entryDao.clearCategory("c1")

        assertNull(entryDao.getById("e1")!!.categoryId)
        assertNull(entryDao.getById("e2")!!.categoryId)
        // 条目本身必须保留——删除分类只回退归属，不删数据
        assertEquals(2, entryDao.count())
    }

    /** FR-5 收藏：单列更新，不影响其他字段。 */
    @Test
    fun setFavoriteOnlyTouchesFavorite() = runBlocking {
        entryDao.upsert(entry("e1", "A"))

        entryDao.setFavorite("e1", true)

        val stored = entryDao.getById("e1")!!
        assertTrue(stored.favorite)
        assertEquals("A", stored.title)
        assertEquals("ACCOUNT", stored.type)
    }
}
