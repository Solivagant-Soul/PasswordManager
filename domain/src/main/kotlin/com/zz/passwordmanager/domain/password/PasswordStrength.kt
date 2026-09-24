package com.zz.passwordmanager.domain.password

/**
 * 密码强度评估（FR-14/T37）。
 *
 * **定位是给用户的直观提示，不是安全性宣称**——所以只用启发式，不引入 zxcvbn
 * 之类的黑盒（体积大、不可解释、也不适合在离线环境跑）。
 * 弱密码的判定口径直接复用 [PasswordPolicy]（主密码强度规则），
 * 安全中心与生成器评级才不会各说各话。
 */
object PasswordStrength {

    enum class Level {
        WEAK, MEDIUM, STRONG, VERY_STRONG;

        val label: String
            get() = when (this) {
                WEAK -> "弱"
                MEDIUM -> "中"
                STRONG -> "强"
                VERY_STRONG -> "非常强"
            }

        /** 强度条填充比例（0f..1f），供 UI 直接使用。 */
        val fraction: Float
            get() = when (this) {
                WEAK -> 0.25f
                MEDIUM -> 0.5f
                STRONG -> 0.75f
                VERY_STRONG -> 1f
            }
    }

    /**
     * 评估强度。规则（启发式，按字符类数与长度分档）：
     *
     * | 条件 | 等级 |
     * |---|---|
     * | 满足 [PasswordPolicy] 的弱密码条件（<8 位 / 单一字符类 / 纯空白 / 控制字符） | WEAK |
     * | 长度 < 12 或 字符类 < 3 | MEDIUM |
     * | 长度 ≥ 12 且 字符类 = 3 | STRONG |
     * | 长度 ≥ 16 且 字符类 = 4 | VERY_STRONG |
     */
    fun evaluate(password: CharArray): Level {
        if (PasswordPolicy.issues(password).isNotEmpty()) return Level.WEAK

        val classes = charClasses(password)
        return when {
            password.size >= 16 && classes >= 4 -> Level.VERY_STRONG
            password.size >= 12 && classes >= 3 -> Level.STRONG
            password.size < 12 || classes < 3 -> Level.MEDIUM
            else -> Level.STRONG
        }
    }

    fun evaluate(password: String): Level = evaluate(password.toCharArray())

    /** 字符类数（大写/小写/数字/符号，空白不计类——与 PasswordPolicy 一致）。 */
    private fun charClasses(password: CharArray): Int {
        var upper = false
        var lower = false
        var digit = false
        var symbol = false
        for (c in password) {
            when {
                c.isWhitespace() -> Unit
                c.isUpperCase() -> upper = true
                c.isLowerCase() -> lower = true
                c.isDigit() -> digit = true
                else -> symbol = true
            }
        }
        return listOf(upper, lower, digit, symbol).count { it }
    }
}
