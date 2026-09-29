package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.domain.autofill.AutofillCandidate
import com.zz.passwordmanager.domain.autofill.AutofillMatch
import com.zz.passwordmanager.domain.autofill.AutofillMatchPolicy
import com.zz.passwordmanager.domain.autofill.AutofillTarget

/**
 * 自动填充的**自持短生命周期会话**（FR-6，v1.2.0；技术方案 §9.2 的落地）。
 *
 * ## 为什么不能复用 [VaultSessionManager]
 *
 * UI 那条会话是「解锁到自动锁定」的长会话，由 Activity 持有。`AutofillService` 没有
 * Activity、拿不到 `VaultViewModel`；若把 DEK 塞进同一条会话，「先解锁一次 App、
 * 之后手机一直亮屏，隔一小时从任意 App 拉一条凭据」就成了绕过 FR-8 自动锁定的旁路。
 * 所以本类**完全不碰 [VaultSessionManager]**：
 *
 * - 唯一的 DEK 来源是 [UnlockVault]，即必须过主密码（派生 + 解包）才能拿到；
 * - 限流/锁定计数因此与 UI 会话共享（[AuthThrottle] 是单例），爆破面没有被放大；
 * - DEK 只在本对象私有字段里活到 [lock]，调用方（认证 Activity）在 `finally` 与
 *   `onDestroy` 里都会调它，用后即清。
 *
 * ## 三条硬约束（各有对应单测）
 *
 * 1. **未认证不得读**：[candidates]/[fill] 在锁定态一律抛 [AutofillSessionException]，
 *    `AutofillService` 拿不到本类，也就无从回答填充请求。
 * 2. **不跨调用驻留**：[lock] 把 DEK 字节清零（与 [Session.clear] 同一手法），
 *    并额外加一道 [ttlMs] 过期：即便调用方漏调 `lock`，超时后 [requireSession]
 *    也会当场清零并按锁定处理。
 * 3. **作用域收窄**：一次会话只能读它自己 [candidates] 展示过的那几条，
 *    拿到任意 entryId 也填不出来。
 */
class AutofillVaultSession(
    private val unlockVault: UnlockVault,
    private val reader: AutofillVaultReader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private val guard = Any()

    private var session: Session? = null
    private var expiresAtMs: Long = 0L

    /** 本次会话已被 [candidates] 放行过的条目；[lock] 时清空。 */
    private var inScope: Set<String> = emptySet()

    /** 仅供 UI 展示当前是否已认证；**不暴露** DEK 或任何可解密的能力。 */
    val isUnlocked: Boolean
        get() = synchronized(guard) { live() != null }

    /**
     * 用主密码开启一次会话（唯一入口）。
     *
     * 复用 [UnlockVault] 而非自己再写一遍派生，是为了让「输错 5 次锁 1 分钟」
     * 这条规则对自动填充同样生效——否则填充路径会变成一个不受限流保护的爆破面。
     * 失败时不留任何状态。
     */
    suspend fun unlock(masterPassword: CharArray): Result<Unit> {
        val result = unlockVault.invoke(masterPassword)
        result.onSuccess { fresh ->
            synchronized(guard) {
                session?.clear() // 上一轮的残留（理论上已过期）先清
                session = fresh
                expiresAtMs = clock() + ttlMs
                inScope = emptySet()
            }
        }
        return result.map { }
    }

    /**
     * 匹配并返回候选（**不含密码**）。
     *
     * 锁定或已过期即抛——这是「服务在锁定态无法回答填充请求」的那道门。
     * 无匹配时返回空列表（S39），调用方据此提示而不填入。
     */
    suspend fun candidates(target: AutofillTarget): List<AutofillCandidate> {
        val dek = requireSession().dek
        val matched = AutofillMatchPolicy.match(target, reader.listCandidates(dek))
        val items = (matched as? AutofillMatch.Candidates)?.items ?: return emptyList()
        synchronized(guard) { inScope = inScope + items.map { it.entryId } }
        return items
    }

    /**
     * 取单条明文用于回填。
     *
     * [entryId] 必须落在本会话 [candidates] 展示过的集合内，否则拒绝：
     * 会话作用域应当只覆盖「这一屏上用户看得见的那几条」，而不是整库。
     */
    suspend fun fill(entryId: String): Result<AutofillFill> {
        val dek = requireSession().dek
        if (entryId !in synchronized(guard) { inScope }) {
            return Result.failure(
                AutofillSessionException(AutofillSessionError.OUT_OF_SCOPE),
            )
        }
        val fill = reader.readFill(dek, entryId)
            ?: return Result.failure(AutofillSessionException(AutofillSessionError.NOT_FOUND))
        return Result.success(fill)
    }

    /** 清 DEK、清作用域。幂等——多处生命周期回调都会调它。 */
    fun lock() {
        synchronized(guard) {
            session?.clear()
            session = null
            inScope = emptySet()
            expiresAtMs = 0L
        }
    }

    /**
     * 取当前会话，顺带做两道检查：非空、且未过期。
     *
     * 过期检查写在这里而不是只在 [unlock] 里设一个标志，是为了堵「调用方忘了 lock()」
     * 这一条路径：到期后的第一次访问自己把 DEK 清掉并按锁定处理。
     */
    private fun requireSession(): Session = synchronized(guard) {
        live() ?: throw AutofillSessionException(AutofillSessionError.LOCKED)
    }

    /** 调用方必须已持有 [guard]。 */
    private fun live(): Session? {
        val current = session ?: return null
        if (clock() >= expiresAtMs) {
            current.clear()
            session = null
            inScope = emptySet()
            return null
        }
        return current
    }

    internal companion object {
        /**
         * 会话有效期上限。
         *
         * 取 2 分钟：认证 Activity 从「输主密码」到「点定一条」正常在十几秒内走完，
         * 2 分钟足够宽松；而 60 秒以上是 FR-8 自动锁定的档位量级，刻意让两者同量级，
         * 免得出现「UI 侧早就自动锁了、填充会话还活着」这种看起来更宽松的状态。
         */
        const val DEFAULT_TTL_MS: Long = 120_000L
    }
}

/** 自动填充会话的失败原因。刻意与 [AuthError] 分开：两者的处置完全不同。 */
enum class AutofillSessionError {
    /** 未认证，或已过期（两者对调用方是同一件事：没有可用会话）。 */
    LOCKED,

    /** 越权：请求的条目不在本次会话展示过的集合内。 */
    OUT_OF_SCOPE,

    /** 条目已不存在（展示与点选之间被删）。 */
    NOT_FOUND,
}

class AutofillSessionException(val error: AutofillSessionError) :
    Exception("autofill session: $error")
