package com.zz.passwordmanager.domain.password

/**
 * 密码生成器选项（v2.1/D4）。
 *
 * 提升为**全局偏好**的原因：编辑页的「⚡生成」要零弹窗直填，而生成器页与编辑页
 * 必须用同一套参数——所以这份配置不能是页面内的临时 `remember` 状态，
 * 得经 [com.zz.passwordmanager.domain.preferences.UserPreferences] 落到 DataStore。
 *
 * 全字段默认 `true`：首次安装时 DataStore 里没有这些键，若按 `booleanPreferencesKey`
 * 的天然默认 `false` 回落，用户会拿到一个**空字符集**，生成按钮永远失败。
 */
data class GeneratorOptions(
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigit: Boolean = true,
    val useSymbol: Boolean = true,
    val excludeAmbiguous: Boolean = true,
) {
    /** 映射为生成器的字符集；可能为空集（用户把开关都关了），调用方需自行处理。 */
    fun charSets(): Set<PasswordGenerator.CharSet> = buildSet {
        if (useUpper) add(PasswordGenerator.CharSet.UPPER)
        if (useLower) add(PasswordGenerator.CharSet.LOWER)
        if (useDigit) add(PasswordGenerator.CharSet.DIGIT)
        if (useSymbol) add(PasswordGenerator.CharSet.SYMBOL)
    }

    /** 与 [defaultPasswordLength] 组合成完整生成规则。 */
    fun rules(length: Int): PasswordGenerator.Rules =
        PasswordGenerator.Rules(length = length, sets = charSets(), excludeAmbiguous = excludeAmbiguous)
}
