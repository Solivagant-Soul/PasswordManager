package com.zz.passwordmanager.domain.password

/** 主密码强度问题（需求 §6.1）。空集合 = 通过。 */
enum class PasswordIssue {
    TOO_SHORT,
    SINGLE_CLASS,
    BLANK_ONLY,
    CONTROL_CHARS,
}

/**
 * 主密码强度策略（Q12：≥8 位 + 至少两类字符）。
 *
 * 字符分类按需求 §6.1 字面：大写字母 / 小写字母 / 数字 / 符号四类取二；
 * 全角与 Unicode 文字归入符号类（S8：全角、emoji 应可接受）；
 * 空白字符不计类（内部空格允许，纯空白由 BLANK_ONLY 拒绝）；
 * 「首尾纯空白」落地为：trim 后为空即拒绝（S8 解读，见需求 §6.1 字符范围）。
 */
object PasswordPolicy {

    const val MIN_LENGTH = 8

    fun issues(password: CharArray): Set<PasswordIssue> {
        val result = mutableSetOf<PasswordIssue>()
        if (password.size < MIN_LENGTH) result += PasswordIssue.TOO_SHORT
        if (password.all { it.isWhitespace() }) result += PasswordIssue.BLANK_ONLY
        if (password.any { it.isISOControl() }) result += PasswordIssue.CONTROL_CHARS
        if (charClassCount(password) < 2) result += PasswordIssue.SINGLE_CLASS
        return result
    }

    private fun charClassCount(password: CharArray): Int {
        var upper = false
        var lower = false
        var digit = false
        var symbol = false
        for (c in password) {
            when {
                c.isWhitespace() -> Unit // 不计类
                c.isUpperCase() -> upper = true
                c.isLowerCase() -> lower = true
                c.isDigit() -> digit = true
                else -> symbol = true
            }
        }
        return listOf(upper, lower, digit, symbol).count { it }
    }
}
