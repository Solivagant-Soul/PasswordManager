package com.zz.passwordmanager.domain.autofill

/**
 * 自动填充的匹配策略（FR-6，v1.2.0）。
 *
 * 放在 `:domain`（纯 Kotlin、不碰任何 Android 类型）是刻意的：AutofillService 拿到的
 * `AssistStructure` 属于框架模型，把它留在 `:app`、只把「目标特征 + 候选条目」喂进这里，
 * 匹配规则就能被 JVM 单测直接覆盖（需求 §10.6 的三个场景）。
 *
 * **候选里刻意没有密码字段**（见 [AutofillCandidate]）：匹配发生在「展示候选」这一步，
 * 那一步不需要、也不应该碰到明文。密码只在用户点定某一条之后、经
 * `AutofillVaultSession.fill()` 单条取出。
 */

/** 目标表单的特征。由 :app 从 `AssistStructure` 提取，两个字段都可能为 null。 */
data class AutofillTarget(
    /** 宿主 App 包名（浏览器里通常是浏览器自己，故常与站点无关）。 */
    val packageName: String?,
    /** WebView 的 `webDomain`；原生 App 一般为 null。 */
    val domain: String?,
)

/**
 * 候选条目（**不含密码**）。
 *
 * 密码不进这里，是本功能的安全底线之一：候选列表会在系统 UI 里铺开、停留任意久，
 * 一旦这里带上明文，「候选」就等于「已解密且长期驻留的凭据」。
 */
data class AutofillCandidate(
    val entryId: String,
    val title: String,
    val username: String,
    val url: String?,
)

/** 匹配结果：要么有候选，要么明确「无匹配」（S39 要提示而不填入）。 */
sealed interface AutofillMatch {
    data object None : AutofillMatch
    data class Candidates(val items: List<AutofillCandidate>) : AutofillMatch
}

/**
 * 目标 → 候选的匹配与排序。
 *
 * 排序而非过滤成一条，是需求 S41 的要求（多条候选时提供选择，不盲目填第一个）。
 * 分数越小越靠前，**同分保持传入顺序**（列表按标题排序，用户心智里"第一条"是稳定的）。
 */
object AutofillMatchPolicy {

    // 分数档位。写成常量而非魔法数字：下面的 when 每加一档都要对应地在这里留名。
    private const val SCORE_DOMAIN_EXACT = 0
    private const val SCORE_DOMAIN_PARTIAL = 1
    private const val SCORE_PACKAGE_IN_URL = 2
    private const val SCORE_PACKAGE_IN_TITLE = 3
    private const val SCORE_UNRANKED = 4

    /**
     * 有目标特征时：零命中返回 [AutofillMatch.None]（S39）；
     * 目标特征完全为空（拿不到包名也拿不到域名）时返回全部候选——
     * 那种表单本就无法识别（S40），此时「展示全部由用户挑」优于「什么都不给」。
     */
    fun match(target: AutofillTarget, candidates: List<AutofillCandidate>): AutofillMatch {
        if (candidates.isEmpty()) return AutofillMatch.None

        val host = target.domain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val pkg = target.packageName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (host == null && pkg == null) {
            return AutofillMatch.Candidates(candidates)
        }

        val scored = candidates.map { it to score(it, host, pkg) }
        val hit = scored.filter { it.second < SCORE_UNRANKED }
        if (hit.isEmpty()) return AutofillMatch.None
        // 稳定排序：sortedBy 是稳定排序，同分保持原顺序。
        return AutofillMatch.Candidates(hit.sortedBy { it.second }.map { it.first })
    }

    private fun score(
        candidate: AutofillCandidate,
        host: String?,
        pkg: String?,
    ): Int {
        val candidateHost = candidate.url?.let(::hostOf)

        if (host != null) {
            if (candidateHost == host) return SCORE_DOMAIN_EXACT
            // 「.example.com 命中 example.com」：站点主域常常不带 www，
            // 而 webDomain 给的是全域名，反向包含能兜住这一类。
            if (candidateHost != null && (candidateHost.endsWith(".$host") || host.endsWith(".$candidateHost"))) {
                return SCORE_DOMAIN_PARTIAL
            }
        }

        if (pkg != null) {
            // 包名没有域名那么规整，只能退化到子串包含。com.github.android → github。
            val token = packageToken(pkg)
            if (token.isNotEmpty()) {
                if (candidateHost != null && candidateHost.contains(token)) return SCORE_PACKAGE_IN_URL
                if (candidate.title.lowercase().contains(token) ||
                    candidate.username.lowercase().contains(token)
                ) {
                    return SCORE_PACKAGE_IN_TITLE
                }
            }
        }

        // 没有任何一条规则命中：交给 match() 判为「无匹配」（S39），
        // 绝不降级成「随便给一条」——那正是需求里点名要避免的盲目填充。
        return SCORE_UNRANKED
    }
    /** 取 URL 的 host 部分并小写；非 http(s) 或无 host 时返回 null。 */
    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        val afterScheme = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed
        val host = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':') // 去端口
            .lowercase()
        return host.takeIf { it.isNotEmpty() && it.contains('.') }
    }

    /**
     * 包名取最有辨识度的中段：`com.example.myapp` → `example`。
     * 取第二段而不是末段，是因为末段常常是 `app`/`android`/`mobile` 这类通用词，
     * 拿它去子串匹配会命中一大片无关条目。
     */
    private fun packageToken(pkg: String): String {
        val segments = pkg.split('.').filter { it.isNotEmpty() }
        if (segments.size < 2) return segments.firstOrNull().orEmpty()
        return segments[1]
    }
}
