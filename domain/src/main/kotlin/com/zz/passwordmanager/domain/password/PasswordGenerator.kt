package com.zz.passwordmanager.domain.password

import java.security.SecureRandom

/**
 * 密码生成器（FR-4/T19）。
 *
 * 字符分类沿用 [PasswordPolicy] 的语义（大写/小写/数字/符号四类），但**不复用其
 * `charClassCount`**——那个是 private 且只返回「有几类」，而生成器需要的是
 * 「用户勾选了哪几类」。另有两处语义差异：长度区间是 4–32（policy 最短 8），
 * 以及 policy 的 CONTROL_CHARS / BLANK_ONLY 规则对生成器无意义。
 *
 * 随机源固定为 [SecureRandom]（不注入 `kotlin.random.Random`：`Random` 的默认实现
 * 在部分平台可能是弱 LCG，而这是密码生成器）。测试经 `random` 参数注入确定性序列。
 */
object PasswordGenerator {

    /** 长度区间（feature 04 边界场景：4 与 32 均为合法值；v2.1 由 64 收窄为 32）。 */
    const val LENGTH_MIN = 4
    const val LENGTH_MAX = 32

    /** 可选字符集（四类，与需求 §6.1 的分类一致）。 */
    enum class CharSet(val chars: String) {
        UPPER("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        LOWER("abcdefghijklmnopqrstuvwxyz"),
        DIGIT("0123456789"),
        SYMBOL("!@#\$%^&*()-_=+[]{};:,.<>?/~"),
    }

    /**
     * 易混淆字符（feature 04「排除易混淆字符」）：手输时易看错/敲错的一组。
     * 含 `|` 而非引号等排版敏感字符，避免生成结果在粘贴到其他地方时被截断。
     */
    const val AMBIGUOUS_CHARS = "0O1lI|"

    /** 生成规则。默认给一组安全的全集。 */
    data class Rules(
        val length: Int = 16,
        val sets: Set<CharSet> = CharSet.entries.toSet(),
        val excludeAmbiguous: Boolean = true,
    )

    enum class Reason { NO_CHARSET, LENGTH_OUT_OF_RANGE }

    class GeneratorException(val reason: Reason) : Exception(reason.name)

    /**
     * 按规则生成密码。
     *
     * 语义要点：**只要求「至少勾选一类字符集」，不要求结果里每一类都出现**——
     * 需求 §10.4 的原文是「选择长度与至少一类字符集并生成」。
     * 均匀采样（每类按其字符数占比）即可满足，无需强制各占位。
     *
     * @throws GeneratorException 未勾选字符集或长度越界（由 Result.failure 返回）
     */
    fun generate(
        rules: Rules,
        random: SecureRandom = SecureRandom(),
    ): Result<CharArray> {
        if (rules.length !in LENGTH_MIN..LENGTH_MAX) {
            return Result.failure(GeneratorException(Reason.LENGTH_OUT_OF_RANGE))
        }
        if (rules.sets.isEmpty()) {
            return Result.failure(GeneratorException(Reason.NO_CHARSET))
        }

        val excluded = if (rules.excludeAmbiguous) AMBIGUOUS_CHARS.toSet() else emptySet()
        val pool = buildString { rules.sets.forEach { append(it.chars) } }
            .filterNot { it in excluded }
        if (pool.isEmpty()) {
            // 仅在勾选集合全为易混淆字符时可能发生（当前字符集下不可达，防御式保留）
            return Result.failure(GeneratorException(Reason.NO_CHARSET))
        }

        return Result.success(CharArray(rules.length) { pool[random.nextInt(pool.length)] })
    }
}
