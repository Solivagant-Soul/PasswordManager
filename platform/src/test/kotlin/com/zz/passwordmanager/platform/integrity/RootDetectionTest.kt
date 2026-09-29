package com.zz.passwordmanager.platform.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 检测的**纯逻辑**测试（v2.0.0）。
 *
 * 覆盖三块：每条信号各自的事实 → 观测映射、聚合规则（几条信号才判「可能」）、
 * 以及**聚合结果到具体文案**的映射。文案必须断言原文而不只是断言布尔值——
 * 措辞本身就是这个特性的产品要求（「检测到可能的 root 环境」而不是「已保护」）。
 */
class RootDetectionTest {

    /** 一台未 root 的量产机的典型事实：非 test-keys 签名、系统属性规整、无提权文件。 */
    private val cleanFacts = RootEnvironmentFacts(
        buildTags = "release-keys",
        roDebuggable = "0",
        roSecure = "1",
    )

    // ---------- 事实 → 观测：每条信号都要有独立样本 ----------

    @Test
    fun `su file in any known path flags the su signal with that path as evidence`() {
        listOf(
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/bin/su",
            "/system/app/Superuser.apk",
        ).forEach { path ->
            val observations = RootDetection.observe(RootEnvironmentFacts(existingPaths = setOf(path)))
            val su = observations.getValue(RootSignal.SU_FILES)
            assertTrue("$path 应命中 su 信号", su.hit == true)
            assertEquals("证据应精确到该路径", listOf(path), su.found)
        }
    }

    @Test
    fun `busybox and magisk markers do not cross-trigger the su signal`() {
        val observations = RootDetection.observe(
            RootEnvironmentFacts(existingPaths = setOf("/system/xbin/busybox", "/sbin/.magisk")),
        )
        assertEquals(true, observations.getValue(RootSignal.BUSYBOX_FILES).hit)
        assertEquals(listOf("/sbin/.magisk"), observations.getValue(RootSignal.MAGISK_MARKERS).found)
        // 分区归属错了会直接影响误报率：busybox 命中不应被当成 su 命中
        assertEquals(false, observations.getValue(RootSignal.SU_FILES).hit)
    }

    @Test
    fun `an existing path outside every candidate list is not evidence`() {
        val observations = RootDetection.observe(
            RootEnvironmentFacts(existingPaths = setOf("/data/data/com.zz.passwordmanager/su", "/sdcard/su")),
        )
        assertEquals(false, observations.getValue(RootSignal.SU_FILES).hit)
        assertTrue(observations.getValue(RootSignal.SU_FILES).found.isEmpty())
    }

    @Test
    fun `only whitelisted package names count as a root manager`() {
        val hit = RootDetection.observe(
            RootEnvironmentFacts(installedPackages = setOf("com.topjohnwu.magisk")),
        )
        assertEquals(listOf("com.topjohnwu.magisk"), hit.getValue(RootSignal.ROOT_MANAGER_PACKAGES).found)

        // 白名单外的包名不得被算成 root 证据（探测端若传错，整个结论就废了）
        val other = RootDetection.observe(
            RootEnvironmentFacts(installedPackages = setOf("com.example.notes", "com.zz.passwordmanager")),
        )
        assertEquals(false, other.getValue(RootSignal.ROOT_MANAGER_PACKAGES).hit)
    }

    @Test
    fun `only candidate system partitions count as writable`() {
        val hit = RootDetection.observe(RootEnvironmentFacts(writablePaths = setOf("/system/xbin")))
        assertEquals(listOf("/system/xbin"), hit.getValue(RootSignal.WRITABLE_SYSTEM_PATH).found)

        // `/` 与 `/data` 对普通应用本就可写；把它们算进来会让这条永远命中、失去判别力
        val noise = RootDetection.observe(RootEnvironmentFacts(writablePaths = setOf("/", "/data", "/sdcard")))
        assertEquals(false, noise.getValue(RootSignal.WRITABLE_SYSTEM_PATH).hit)
    }

    @Test
    fun `build tags distinguish test-keys from release-keys`() {
        assertEquals(
            true,
            RootDetection.observe(RootEnvironmentFacts(buildTags = "test-keys")).getValue(RootSignal.TEST_KEYS_BUILD).hit,
        )
        assertEquals(
            false,
            RootDetection.observe(RootEnvironmentFacts(buildTags = "release-keys")).getValue(RootSignal.TEST_KEYS_BUILD).hit,
        )
    }

    @Test
    fun `system properties are matched against the flagged value and nothing else`() {
        val debugged = RootDetection.observe(RootEnvironmentFacts(roDebuggable = "1", roSecure = "1"))
        assertEquals(true, debugged.getValue(RootSignal.DEBUGGABLE_SYSTEM).hit)
        assertEquals(false, debugged.getValue(RootSignal.INSECURE_SYSTEM).hit)

        val insecure = RootDetection.observe(RootEnvironmentFacts(roDebuggable = "0", roSecure = "0"))
        assertEquals(false, insecure.getValue(RootSignal.DEBUGGABLE_SYSTEM).hit)
        assertEquals(true, insecure.getValue(RootSignal.INSECURE_SYSTEM).hit)
    }

    @Test
    fun `unreadable system properties are unknown rather than negative`() {
        val observations = RootDetection.observe(RootEnvironmentFacts(roDebuggable = null, roSecure = null))
        // 「读不到」与「读到 0」必须可区分：前者不能被算成「未命中」以外的任何结论
        assertEquals(null, observations.getValue(RootSignal.DEBUGGABLE_SYSTEM).hit)
        assertEquals(null, observations.getValue(RootSignal.INSECURE_SYSTEM).hit)

        val blank = RootDetection.observe(RootEnvironmentFacts(roDebuggable = ""))
        assertEquals(null, blank.getValue(RootSignal.DEBUGGABLE_SYSTEM).hit)
    }

    // ---------- 聚合规则 ----------

    @Test
    fun `a clean retail device reports nothing found`() {
        val report = RootDetection.evaluate(cleanFacts)

        assertEquals(RootVerdict.NOTHING_FOUND, report.verdict)
        assertFalse(report.suspected)
        assertEquals(0, report.score)
        assertTrue(report.hits.isEmpty())
        assertTrue(report.unknown.isEmpty())
        assertEquals(RootSignal.entries.size, report.checkedCount)
    }

    @Test
    fun `each strong signal alone is enough to be suspected`() {
        val strongAlone: List<RootEnvironmentFacts> = listOf(
            RootEnvironmentFacts(existingPaths = setOf("/system/xbin/su")),
            RootEnvironmentFacts(existingPaths = setOf("/system/bin/busybox")),
            RootEnvironmentFacts(existingPaths = setOf("/data/adb/magisk")),
            RootEnvironmentFacts(installedPackages = setOf("eu.chainfire.supersu")),
            RootEnvironmentFacts(writablePaths = setOf("/system")),
        )

        strongAlone.forEach { facts ->
            val report = RootDetection.evaluate(facts)
            assertEquals("$facts 应判为可疑", RootVerdict.SUSPECTED, report.verdict)
            assertEquals(RootDetection.THRESHOLD, report.score)
            assertEquals(1, report.hits.size)
        }
    }

    @Test
    fun `strong signals accumulate but never downgrade a suspicion`() {
        val report = RootDetection.evaluate(
            RootEnvironmentFacts(
                existingPaths = setOf("/system/xbin/su", "/system/bin/busybox"),
                writablePaths = setOf("/system"),
            ),
        )
        assertEquals(RootVerdict.SUSPECTED, report.verdict)
        assertEquals(9, report.score)
        assertEquals(
            // 强信号排前，页面上一眼能看到最有分量的一条
            listOf(RootSignal.SU_FILES, RootSignal.BUSYBOX_FILES, RootSignal.WRITABLE_SYSTEM_PATH),
            report.hits.map { it.signal },
        )
    }

    @Test
    fun `one or two weak signals are not enough`() {
        val one = RootDetection.evaluate(RootEnvironmentFacts(buildTags = "test-keys"))
        assertEquals(RootVerdict.NOTHING_FOUND, one.verdict)
        assertEquals(1, one.score)
        assertEquals(listOf(RootSignal.TEST_KEYS_BUILD), one.hits.map { it.signal })

        // 弱信号两两组合仍不足以定夺：test-keys 签名 + ro.secure=0 常见于未 root 的
        // OEM userdebug 镜像，凑成第三分才定夺，正是为了压住这类误报
        val two = RootDetection.evaluate(RootEnvironmentFacts(buildTags = "test-keys", roSecure = "0"))
        assertEquals(RootVerdict.NOTHING_FOUND, two.verdict)
        assertEquals(2, two.score)
    }

    @Test
    fun `all three weak signals together reach the threshold`() {
        val report = RootDetection.evaluate(
            RootEnvironmentFacts(buildTags = "test-keys", roDebuggable = "1", roSecure = "0"),
        )
        assertEquals(RootVerdict.SUSPECTED, report.verdict)
        assertEquals(RootDetection.THRESHOLD, report.score)
    }

    @Test
    fun `unknown signals never add to the score and never create a hit`() {
        // 属性读不到（三条弱信号）而路径那几条都读到了：多数信号有结果，就照常给结论
        val report = RootDetection.evaluate(
            RootEnvironmentFacts(buildTags = null, roDebuggable = null, roSecure = null),
        )
        assertEquals(0, report.score)
        assertTrue(report.hits.isEmpty())
        assertEquals(3, report.unknown.size)
        assertEquals(5, report.checkedCount)
        assertEquals(RootVerdict.NOTHING_FOUND, report.verdict)
    }

    @Test
    fun `more unreadable signals than readable ones means no conclusion at all`() {
        // 拿残缺证据说「没查到」等于拿不确定去安抚用户：多数读不到就该明说读不到
        val unreadable = RootSignal.entries.associateWith { RootObservation(hit = null) } +
            mapOf(RootSignal.SU_FILES to RootObservation(hit = false))

        val report = RootDetection.evaluate(unreadable)

        assertEquals(RootVerdict.INCONCLUSIVE, report.verdict)
        assertEquals(0, report.score)
        assertEquals(1, report.checkedCount)
        assertEquals(7, report.unknown.size)
    }

    @Test
    fun `a suspect hit wins even when the rest could not be read`() {
        val partial = mapOf(
            RootSignal.SU_FILES to RootObservation(hit = true, found = listOf("/system/xbin/su")),
            RootSignal.BUSYBOX_FILES to RootObservation(hit = null),
            RootSignal.MAGISK_MARKERS to RootObservation(hit = null),
            RootSignal.ROOT_MANAGER_PACKAGES to RootObservation(hit = null),
            RootSignal.WRITABLE_SYSTEM_PATH to RootObservation(hit = null),
            RootSignal.TEST_KEYS_BUILD to RootObservation(hit = null),
            RootSignal.DEBUGGABLE_SYSTEM to RootObservation(hit = null),
            RootSignal.INSECURE_SYSTEM to RootObservation(hit = null),
        )

        val report = RootDetection.evaluate(partial)

        // 读不到一堆弱信号不得把「已命中」降级成「无法判断」——有证据就是有证据
        assertEquals(RootVerdict.SUSPECTED, report.verdict)
        assertEquals(listOf("/system/xbin/su"), report.hits.single().found)
    }

    @Test
    fun `the checklist keeps every signal in declaration order with its tri-state result`() {
        val report = RootDetection.evaluate(
            RootEnvironmentFacts(
                existingPaths = setOf("/system/xbin/su"),
                buildTags = "release-keys",
                roDebuggable = null,
            ),
        )

        // 页面照这个顺序逐行显示；漏一项或换序都会被这条断言挡住
        assertEquals(RootSignal.entries.toList(), report.checks.map { it.signal })
        assertEquals(
            listOf(true, false, false, false, false, false, null, null),
            report.checks.map { it.hit },
        )
        assertEquals(
            listOf("/system/xbin/su"),
            report.checks.first { it.signal == RootSignal.SU_FILES }.found,
        )
        assertEquals(6, report.checkedCount)
    }

    // ---------- 结论文案（逐字断言） ----------
    @Test
    fun `suspicion is reported as a possibility and never as a resolved risk`() {
        val report = RootDetection.evaluate(RootEnvironmentFacts(existingPaths = setOf("/system/xbin/su")))

        assertEquals("检测到可能的 root 环境", report.headline)
        assertTrue(report.conclusion.contains("命中 1 项线索：常见路径下存在提权文件（su / Superuser.apk）"))
        assertTrue("必须明说不限制使用", report.conclusion.contains("不会因此限制任何功能"))
        assertTrue("必须点明与截屏保护的关系", report.conclusion.contains("允许截屏"))
        assertTrue("必须声明不联网", report.conclusion.contains("不联网"))
        assertNotNull(report.conclusion)
    }

    @Test
    fun `a clean result admits it is not a safety guarantee`() {
        val report = RootDetection.evaluate(cleanFacts)

        assertEquals("未发现明显的 root 线索", report.headline)
        assertTrue(report.conclusion.contains("不等于「设备是安全的」"))
        assertTrue(report.conclusion.contains("bootloader"))
        assertTrue(report.conclusion.contains("不联网"))
    }

    @Test
    fun `an inconclusive read says neither clean nor compromised`() {
        val report = RootDetection.evaluate(
            RootSignal.entries.associateWith { RootObservation(hit = null) },
        )

        assertEquals("未能完成检查", report.headline)
        assertTrue(report.conclusion.contains("既不能说明设备干净"))
        assertTrue(report.conclusion.contains("不联网"))
    }

    @Test
    fun `no wording ever claims the app is protected or a risk removed`() {
        val forbidden = listOf("已保护", "不安全", "已阻止", "已加固", "已拦截", "风险已消除", "安全无忧")

        val reports = listOf(
            RootDetection.evaluate(RootEnvironmentFacts(existingPaths = setOf("/system/xbin/su"))),
            RootDetection.evaluate(cleanFacts),
            RootDetection.evaluate(RootEnvironmentFacts()),
        )
        reports.forEach { report ->
            val text = report.headline + report.conclusion
            forbidden.forEach { word ->
                assertFalse("文案不应出现「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun `evidence travels from the facts into the report`() {
        val report = RootDetection.evaluate(
            RootEnvironmentFacts(
                existingPaths = setOf("/sbin/su", "/data/local/bin/su", "/system/bin/busybox"),
                installedPackages = setOf("com.topjohnwu.magisk"),
            ),
        )
        // su 命中的两条路径按候选表顺序（而非 Set 的任意顺序）报出，页面显示才稳定
        assertEquals(listOf("/sbin/su", "/data/local/bin/su"), report.hits[0].found)
        assertEquals(listOf("/system/bin/busybox"), report.hits[1].found)
        assertEquals(listOf("com.topjohnwu.magisk"), report.hits[2].found)
    }

    /**
     * 结论**只提示、不拦截**：这是产品硬决策（Q-14），用枚举成员数做成绊线，
     * 将来若有人加了 BLOCKED/REFUSE 之类的取值，这条测试会当场失败。
     */
    @Test
    fun `verdicts have exactly the three warn-only values`() {
        assertEquals(
            listOf(RootVerdict.SUSPECTED, RootVerdict.NOTHING_FOUND, RootVerdict.INCONCLUSIVE),
            RootVerdict.entries.toList(),
        )
    }
}
