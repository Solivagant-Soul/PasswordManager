package com.zz.passwordmanager.domain.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配策略单测（FR-6）。对应需求 §10.6 的三个 Gherkin 场景：
 * 填入成功（S38）、无匹配不填（S39）、多条候选给选择而非盲目填第一条（S41）。
 */
class AutofillMatchPolicyTest {

    private fun candidate(
        id: String,
        title: String,
        username: String = "user",
        url: String? = null,
    ) = AutofillCandidate(id, title, username, url)

    @Test
    fun `域名精确命中排第一`() {
        val github = candidate("1", "GitHub", url = "https://github.com/login")
        val gitlab = candidate("2", "GitLab", url = "https://gitlab.com/login")

        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = "com.android.chrome", domain = "github.com"),
            listOf(gitlab, github),
        )

        val items = (result as AutofillMatch.Candidates).items
        // gitlab.com 不得混入 github.com 的候选——这正是 S39「无匹配不填」的底线。
        assertEquals(listOf("1"), items.map { it.entryId })
    }

    @Test
    fun `www 前缀按主域互通`() {
        // 目标表单给的 webDomain 常带 www，条目里存的不带——不互通就等于永远无匹配。
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = null, domain = "www.example.com"),
            listOf(candidate("1", "示例", url = "https://example.com/login")),
        )

        assertTrue(result is AutofillMatch.Candidates)
    }

    @Test
    fun `无匹配返回 None 而非随便给一条`() {
        // S39：这是本策略最要紧的一条——宁可不填，也不能「盲填第一个」。
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = "com.example.bank", domain = null),
            listOf(candidate("1", "GitHub", url = "https://github.com")),
        )

        assertEquals(AutofillMatch.None, result)
    }

    @Test
    fun `包名取第二段去匹配标题与网址`() {
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = "com.github.android", domain = null),
            listOf(
                candidate("1", "某个完全无关的站", url = "https://example.org"),
                candidate("2", "GitHub", url = null),
            ),
        )

        val items = (result as AutofillMatch.Candidates).items
        assertEquals(listOf("2"), items.map { it.entryId })
    }

    @Test
    fun `多候选保留全部并按分数排序`() {
        // S41：同站多条时给列表，而不是只留一条让人没得选。
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = null, domain = "github.com"),
            listOf(
                candidate("1", "工作号", username = "a@corp.com", url = "https://github.com"),
                candidate("2", "个人号", username = "b@me.com", url = "https://github.com/settings"),
                candidate("3", "别处", username = "c@me.com", url = "https://gitlab.com"),
            ),
        )

        val items = (result as AutofillMatch.Candidates).items
        assertEquals(listOf("1", "2"), items.map { it.entryId })
    }

    @Test
    fun `拿不到任何目标特征时展示全部`() {
        // S40 的降级：连包名和域名都没有的表单本就无法识别，
        // 此时「全给出来让用户挑」优于「什么都不给」。
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = null, domain = null),
            listOf(candidate("1", "A"), candidate("2", "B")),
        )

        assertEquals(listOf("1", "2"), (result as AutofillMatch.Candidates).items.map { it.entryId })
    }

    @Test
    fun `空候选恒为 None`() {
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = "com.x.y", domain = "x.com"),
            emptyList(),
        )

        assertEquals(AutofillMatch.None, result)
    }

    @Test
    fun `包名只有一段时不至于把通用词当特征`() {
        // com.app 这种：第二段取不到，回落成整包名匹配，
        // 绝不能因为「包名里有个 app」就把一堆条目捞出来。
        val result = AutofillMatchPolicy.match(
            AutofillTarget(packageName = "app", domain = null),
            listOf(candidate("1", "我的应用", url = null)),
        )

        assertEquals(AutofillMatch.None, result)
    }

    @Test
    fun `候选模型里没有任何密码字段`() {
        // 安全底线：候选会铺在系统 UI 上、停留任意久，绝不能携带明文。
        val names = AutofillCandidate::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue("候选模型出现了密码相关字段: $names", names.none { it.contains("pass") || it.contains("secret") })
    }
}
