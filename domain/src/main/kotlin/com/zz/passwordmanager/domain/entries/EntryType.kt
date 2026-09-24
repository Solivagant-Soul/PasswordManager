package com.zz.passwordmanager.domain.entries

/**
 * 条目类型（FR-15，v0.8 设计稿）。
 *
 * 存在的意义：不同类型的条目，列表副标题该显示什么**不一样**。设计稿的示例
 * ——账号显示用户名；银行卡显示掩码卡号（`6225 **** 9012`）；WiFi 显示网络标识
 * （`TP-LINK_5G`）。若不加类型，同一份「用户名」字段对银行卡就是明文卡号，
 * 会直接暴露在列表里。
 *
 * 存储：`entries.type` 为枚举名（`ACCOUNT` 等），解析失败一律回退 [ACCOUNT]——
 * 宁可按最保守的方式显示，也不要因为一个坏值就把卡号明文摊出来。
 */
enum class EntryType {
    /** 账号：副标题显示用户名原文（如 user@example.com） */
    ACCOUNT,

    /** 银行卡：副标题显示**掩码卡号**（保留前 4 后 4），防列表页泄露 */
    BANK_CARD,

    /** WiFi：副标题显示网络标识原文（如 TP-LINK_5G） */
    WIFI,

    /** 证件：副标题显示**掩码证件号**（保留前 4 后 4） */
    DOCUMENT,
    ;

    /**
     * 列表副标题。`username` 字段承载各类型对应的标识：
     * 账号→用户名、银行卡→卡号、WiFi→SSID、证件→证件号。
     */
    fun subtitle(username: String): String = when (this) {
        ACCOUNT, WIFI -> username
        BANK_CARD, DOCUMENT -> mask(username)
    }

    /** 中文显示名（详情页/编辑页用）。 */
    val displayName: String
        get() = when (this) {
            ACCOUNT -> "账号"
            BANK_CARD -> "银行卡"
            WIFI -> "WiFi"
            DOCUMENT -> "证件"
        }

    /** 卡片类类型是否需要掩码——列表渲染据此决定是否要「显示/隐藏」副标题。 */
    val needsMasking: Boolean
        get() = this == BANK_CARD || this == DOCUMENT

    companion object {
        /** 宽松解析：未知/空值回退 [ACCOUNT]（与 schema 默认值一致）。 */
        fun from(raw: String?): EntryType =
            entries.firstOrNull { it.name == raw } ?: ACCOUNT

        /** 掩码：保留前 4 后 4；长度不足以分段时按比例隐藏。 */
        fun mask(value: String): String = when {
            value.length <= MASK_LEAD -> "*".repeat(value.length)
            value.length <= MASK_LEAD + MASK_TAIL ->
                // 不足以「前 4 + 至少 1 位星号 + 后 4」，退化为只露前 1 位
                value.first() + "*".repeat(value.length - 1)
            else -> value.take(MASK_LEAD) + MASK_FILLER + value.takeLast(MASK_TAIL)
        }

        private const val MASK_LEAD = 4
        private const val MASK_TAIL = 4
        /** 两侧留空格，读起来像真的打码（设计稿示例 `6225 **** 1234`） */
        private const val MASK_FILLER = " **** "
    }
}
