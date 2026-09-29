package com.zz.passwordmanager.platform.integrity

/*
 * v2.0.0 root 环境检测（FR-10 / NFR-S6 / Q-14「仅提示风险」）。
 *
 * 本文件是**纯逻辑半边**：不含任何 Android API，可在 JVM 单测里完整跑通。
 * 平台调用那半边在 [AndroidRootEnvironmentProbe]，两半之间的缝是 [RootEnvironmentFacts]——
 * 「采到的事实」与「这些事实意味着什么」分开，好处是聚合规则与文案都能被测试覆盖，
 * 而不必真的弄一台已 root 的机器。
 *
 * 三条不可动摇的前提（改动前请先读）：
 *
 * 1. **本机自用 APK，不走 Google Play。** 故 Play Integrity / SafetyNet / Device Recognition
 *    一概不可用——没有 Play 依赖，也不允许为此引入。检测只能是本地启发式，
 *    而**每一条本地启发式都会被懂行的人绕过**。文案因此绝不能说「已保护」「风险已消除」。
 * 2. **只提示，不拦截。** [RootVerdict] 只有三种取值，没有也永远不会有「是否允许继续」；
 *    本 App 在已 root 的设备上必须完全可用。
 * 3. **不加依赖、不加权限。** 全部信号只用已声明的 API。
 */

/**
 * 一条信号。
 *
 * [weight] 分两档是为了对付**误报**：强信号（3 分）单项即可定夺，弱信号（1 分）要凑够三条
 * 才定夺。弱信号单独出现时往往并不是 root——test-keys 签名、ro.secure=0 同样出现在部分
 * OEM 的 userdebug 镜像上，而那台机器未必被 root 过。
 */
enum class RootSignal(val weight: Int, val label: String) {
    /** 常见路径下存在 su / Superuser.apk 等提权文件。 */
    SU_FILES(3, "常见路径下存在提权文件（su / Superuser.apk）"),

    /** 常见路径下存在 busybox——几乎只在 root 环境里被预置。 */
    BUSYBOX_FILES(3, "常见路径下存在 busybox"),

    /** 已知 root 管理器应用可被解析到（白名单见 [RootDetection.ROOT_MANAGER_PACKAGES]）。 */
    ROOT_MANAGER_PACKAGES(3, "系统中装有已知的 root 管理器应用"),

    /** Magisk 特有的文件或目录。 */
    MAGISK_MARKERS(3, "存在 Magisk 特有的文件或目录"),

    /** /system、/vendor 等系统分区对本应用可写——正常设备上不可能。 */
    WRITABLE_SYSTEM_PATH(3, "系统分区可写"),

    /** Build.TAGS 含 test-keys：非官方发布镜像。 */
    TEST_KEYS_BUILD(1, "系统镜像是 test-keys 签名（非官方发布镜像）"),

    /** ro.debuggable=1：系统以可调试方式构建。 */
    DEBUGGABLE_SYSTEM(1, "ro.debuggable=1（系统以可调试方式构建）"),

    /** ro.secure=0：未启用安全启动链校验。 */
    INSECURE_SYSTEM(1, "ro.secure=0（系统未启用安全启动链校验）"),
}

/** 平台层采到的事实。仅原始观测，不含判断——判断在 [RootDetection]。 */
data class RootEnvironmentFacts(
    /** 候选路径中**实际存在**的那些（探测哪批路径是 [RootDetection] 的常量）。 */
    val existingPaths: Set<String> = emptySet(),
    /** 已知 root 管理器中**能解析到**的那些包名。 */
    val installedPackages: Set<String> = emptySet(),
    /** 候选系统分区中**可写**的那些。 */
    val writablePaths: Set<String> = emptySet(),
    /** Build.TAGS；null = 读不到（未知，不是「没有」）。 */
    val buildTags: String? = null,
    /** ro.debuggable；null = 读不到。 */
    val roDebuggable: String? = null,
    /** roSecure；null = 读不到。 */
    val roSecure: String? = null,
)

/**
 * 单条信号的三态观测。
 *
 * 三态而非布尔是有意为之：`android.os.SystemProperties` 是非 SDK 接口，API 28+ 的
 * 隐藏 API 限制可能让它抛异常。把「读不到」如实记成 [hit] 为 null，既不虚报「未发现」，
 * 也不虚报「命中」——[RootReport.unknown] 会单独列出未能检查的项。
 */
data class RootObservation(
    /** true = 命中；false = 未命中；null = 读不到，未参与评分。 */
    val hit: Boolean?,
    /** 命中时的证据（具体路径 / 包名），供页面上如实列出。 */
    val found: List<String> = emptyList(),
)

/** 每条信号的观测，键为全部 [RootSignal]（缺失的键按 null 处理）。 */
typealias RootObservations = Map<RootSignal, RootObservation>

/**
 * 检测结论。**只有三种，没有「阻止」**——见文件头第 2 条前提。
 *
 * [INCONCLUSIVE] 用来区分「查了、没查到」与「压根没查成」：后者不该让用户以为设备干净。
 */
enum class RootVerdict { SUSPECTED, NOTHING_FOUND, INCONCLUSIVE }

/** 一条命中的信号及其证据。 */
data class RootSignalHit(val signal: RootSignal, val found: List<String>)

/**
 * 逐项结果：子页「检查了什么」那张清单的一行。
 *
 * [hit] 为 null = 这条**读不到**（[RootObservation] 的三态），页面上要如实标成
 * 「未能读取」而不是「未发现」——两者对用户的意义完全相反。
 */
data class RootSignalCheck(
    val signal: RootSignal,
    val hit: Boolean?,
    val found: List<String> = emptyList(),
)

data class RootReport(
    val verdict: RootVerdict,
    val score: Int,
    /** 逐项清单，按 [RootSignal] 声明顺序——页面照此顺序显示。 */
    val checks: List<RootSignalCheck>,
    /** 命中的信号，按分量排序（强信号在前），供结论文案引用。 */
    val hits: List<RootSignalHit>,
    /** 成功读到结果的信号数，页面上写「已检查 N 项」用。 */
    val checkedCount: Int,
    /** 短标签：设置主页那一行的右侧值。 */
    val headline: String,
    /** 长文案：子页正文。措辞约束见 [RootDetection.conclusionOf]。 */
    val conclusion: String,
) {
    /** 读不到、因而未参与评分的信号。 */
    val unknown: List<RootSignal> get() = checks.filter { it.hit == null }.map { it.signal }
    val suspected: Boolean get() = verdict == RootVerdict.SUSPECTED
}

/**
 * 纯检测逻辑：候选路径 / 包名常量、事实 → 观测的映射、评分与文案。
 *
 * 评分规则（全仓唯一一处，别处不要另写一遍）：
 * **强信号 3 分、弱信号 1 分，总分 ≥ [THRESHOLD] 判为 [RootVerdict.SUSPECTED]。**
 * 即「一条强信号即可定夺，三条弱信号才定夺，两条弱信号不够」。
 */
object RootDetection {

    const val THRESHOLD = 3

    /**
     * su / Superuser 的候选路径。覆盖 AOSP 各分区与常见 Magisk 落点。
     * 只 stat 这些已知名，不做全盘搜索——全盘搜索在解锁路径上不可接受。
     */
    val SU_PATHS: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/debug_ramdisk/su",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/framework/Superuser.apk",
    )

    val BUSYBOX_PATHS: List<String> = listOf(
        "/system/xbin/busybox",
        "/system/bin/busybox",
        "/sbin/busybox",
        "/su/bin/busybox",
        "/data/local/bin/busybox",
        "/data/local/xbin/busybox",
        "/vendor/bin/busybox",
    )

    /**
     * Magisk 的落点。`/data/adb` 下的路径在现代 Android 上普通应用读不到（目录权限），
     * 读不到时由探测端记为「未知」而不是「未命中」。
     */
    val MAGISK_PATHS: List<String> = listOf(
        "/sbin/.magisk",
        "/debug_ramdisk/.magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/data/adb/ksu",
        "/dev/.magisk",
    )

    /**
     * 候选系统分区。刻意**不含** `/` 与 `/data`：那两处对普通应用本就可写，
     * 列进去只会让「可写」这条永远命中、彻底失去判别力。
     */
    val WRITABLE_SYSTEM_PATHS: List<String> = listOf(
        "/system",
        "/system/bin",
        "/system/xbin",
        "/vendor",
        "/product",
    )

    /**
     * 已知 root 管理器包名。探测端**逐个定向查询**而非拉全量已装列表——
     * `getInstalledPackages` 是一次几十毫秒量级的 Binder 调用外加大量对象分配。
     *
     * 已知局限（不要试图在这里"修"）：API 30+ 的包可见性过滤会让本 App 看不到
     * 未声明 `<queries>` 的第三方应用，故这一条在 Android 11+ 上偏向「未命中」。
     * 补 `<queries>` 能修，但要为此放宽本 App 的可见包集合——为一个只提示不拦截的
     * 启发式信号不值得，故保留局限并在文案里说明。su/busybox/Magisk 路径那几条不受此限。
     */
    val ROOT_MANAGER_PACKAGES: List<String> = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.alephzain.framaroot",
        "com.zhiqupk.root.global",
        "com.kingroot.kinguser",
        "com.mgyun.shua.su",
        "io.github.huskydg.magisk",
    )

    /** 事实 → 观测。全量返回每个 [RootSignal] 一项，唯一的映射入口，纯函数。 */
    fun observe(facts: RootEnvironmentFacts): RootObservations = buildMap {
        put(RootSignal.SU_FILES, paths(facts.existingPaths, SU_PATHS))
        put(RootSignal.BUSYBOX_FILES, paths(facts.existingPaths, BUSYBOX_PATHS))
        put(RootSignal.MAGISK_MARKERS, paths(facts.existingPaths, MAGISK_PATHS))
        // 只认白名单内的包名：探测端若传进别的包，不该被算成 root 证据
        put(
            RootSignal.ROOT_MANAGER_PACKAGES,
            paths(facts.installedPackages, ROOT_MANAGER_PACKAGES),
        )
        put(RootSignal.WRITABLE_SYSTEM_PATH, paths(facts.writablePaths, WRITABLE_SYSTEM_PATHS))
        put(RootSignal.TEST_KEYS_BUILD, flag(facts.buildTags?.contains("test-keys") ?: null))
        // 属性读不到时是「未知」而非「未命中」：「读不到」与「读到 0」是两回事
        put(RootSignal.DEBUGGABLE_SYSTEM, flag(facts.roDebuggable.toFlagValue("1")))
        put(RootSignal.INSECURE_SYSTEM, flag(facts.roSecure.toFlagValue("0")))
    }

    /** 观测 → 报告。 */
    fun evaluate(observations: RootObservations): RootReport {
        // 清单按声明顺序输出（页面读起来是「从文件到属性」），
        // 命中项另按分量排序，让结论文案第一句就是最有分量的那条。
        val checks = observations.entries
            .sortedBy { it.key.ordinal }
            .map { (signal, observation) -> RootSignalCheck(signal, observation.hit, observation.found) }
        val hits = observations.filterValues { it.hit == true }.keys.sortedByDescending { it.weight }
        val unknownCount = observations.values.count { it.hit == null }
        val score = hits.sumOf { it.weight }
        val verdict = when {
            score >= THRESHOLD -> RootVerdict.SUSPECTED
            // 读不到的信号比读到的还多，就不该拿「没查到」当结论说——那是在拿残缺证据
            // 安抚用户。三条弱信号（系统属性，反射可能失败）读不到时恰好不触发这条。
            unknownCount * 2 > observations.size -> RootVerdict.INCONCLUSIVE
            else -> RootVerdict.NOTHING_FOUND
        }
        return RootReport(
            verdict = verdict,
            score = score,
            checks = checks,
            hits = hits.map { RootSignalHit(it, observations.getValue(it).found) },
            checkedCount = observations.size - unknownCount,
            headline = headlineOf(verdict),
            conclusion = conclusionOf(verdict, hits.map { it.label }),
        )
    }

    fun evaluate(facts: RootEnvironmentFacts): RootReport = evaluate(observe(facts))

    /** 候选路径与「实际存在」的交集：空 → 未命中，非空 → 命中并带上证据。 */
    private fun paths(present: Set<String>, candidates: List<String>): RootObservation {
        val found = candidates.filter { it in present }
        return RootObservation(hit = found.isNotEmpty(), found = found)
    }

    /** 布尔观测转三态观测（可空值即未知）。 */
    private fun flag(value: Boolean?): RootObservation = RootObservation(hit = value)

    /**
     * 字符串系统属性 → 三态观测：等于期望值为命中，明确的另一个值为未命中，
     * null（反射失败）或空串（读不到内容）为未知。
     */
    private fun String?.toFlagValue(expected: String): Boolean? = when {
        this == null || isEmpty() -> null
        this == expected -> true
        else -> false
    }

    private fun headlineOf(verdict: RootVerdict): String = when (verdict) {
        RootVerdict.SUSPECTED -> "检测到可能的 root 环境"
        RootVerdict.NOTHING_FOUND -> "未发现明显的 root 线索"
        RootVerdict.INCONCLUSIVE -> "未能完成检查"
    }

    /**
     * 长文案。
     *
     * 措辞被约束过，改之前先读：
     * - 不出现「已保护」「不安全」「已阻止」「已加固」——本检测没有改变任何防护强度，
     *   说成「已保护」是虚假信任文案（与 C5 把「端到端加密」改写为「本地加密存储」同类）；
     * - 命中时先说「可能」，紧接着说「不限制使用」，最后才说后果；
     * - 未命中时必须把「没查到 ≠ 安全」讲透——一次骗过用户的误报，
     *   会让他连本 App 后续所有真实的告警一起不信；
     * - 每次都说明这是本地检查、不联网。
     */
    private fun conclusionOf(verdict: RootVerdict, hitLabels: List<String>): String = buildString {
        when (verdict) {
            RootVerdict.SUSPECTED -> {
                append("命中 ${hitLabels.size} 项线索：")
                append(hitLabels.joinToString("、"))
                append("。\n\n")
                append("本应用不会因此限制任何功能：密码库照常加密、照常可用，条目不会少、不会锁。")
                append("是否继续在这台设备上存放重要密码，由你自己判断。\n\n")
                append("最实际的影响是设置里的「允许截屏」：它依赖系统窗口标志，在已 root 的设备上可以被绕过；")
                append("自动锁定、剪贴板 30 秒清理同理都可能失效。")
            }
            RootVerdict.NOTHING_FOUND -> {
                append("已检查的线索全部未命中。\n\n")
                append("但这只说明「这次没查出来」，不等于「设备是安全的」：")
                append("有意隐藏 root 的工具能让上面任何一项都查不出来。")
                append("如果这台设备解锁过 bootloader 或做过 OEM 解锁，请以自己的判断为准。")
            }
            RootVerdict.INCONCLUSIVE -> {
                append("系统限制导致关键线索读不到，这次没有得出结论。\n\n")
                append("这既不能说明设备干净，也不能说明设备已被 root。")
            }
        }
        append("\n\n本检查只在本机进行：读几个文件的「是否存在」、查几个系统属性，不联网、不上传任何信息。")
    }
}
