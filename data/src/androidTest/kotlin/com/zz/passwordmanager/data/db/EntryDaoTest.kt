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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T13 DAO 验收（真 SQLite，in-memory；DAO 逻辑与加密无关）：
 * 归一化搜索、分类/标签过滤、级联删除、计数。
 */
@RunWith(AndroidJUnit4::class)
class EntryDaoTest {

    private lateinit var db: VaultDatabase
    private val entryDao get() = db.entryDao()
    private val tagDao get() = db.tagDao()
    private val entryTagDao get() = db.entryTagDao()

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

    private suspend fun link(entryId: String, tag: String) {
        tagDao.insert(TagEntity(tag))
        entryTagDao.insertAll(listOf(EntryTagEntity(entryId, tag)))
    }

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
    fun searchByTagJoinsEntryTags() = runBlocking {
        entryDao.upsert(entry("e1", "A"))
        entryDao.upsert(entry("e2", "B"))
        link("e1", "work")

        assertEquals(listOf("e1"), entryDao.searchByTag("work", null, "").map { it.id })
        assertTrue(entryDao.searchByTag("nomatch", null, "").isEmpty())
    }

    @Test
    fun countReflectsRows() = runBlocking {
        entryDao.upsert(entry("e1", "A"))
        entryDao.upsert(entry("e2", "B"))

        assertEquals(2, entryDao.count())
    }

    @Test
    fun deletingEntryCascadesTagLinksButKeepsTags() = runBlocking {
        entryDao.upsert(entry("e1", "A"))
        link("e1", "work")

        entryDao.deleteById("e1")

        assertTrue(entryTagDao.tagsFor("e1").isEmpty())
        assertEquals("work", tagDao.listAll().single().name) // 标签保留（分类回退语义一致）
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
}
