package com.zz.passwordmanager.domain.entries

/**
 * 条目字段校验（需求 §6.3）。
 *
 * **抽出来的原因（v1.3.0）**：`EntryRepositoryImpl` 与备份导入链路（`ImportBackup`）
 * 面对的是同一条规则。导入是**绕过编辑页**直接写库的旁路——若它自带一份副本，
 * 两处迟早漂移，届时「UI 拦得住、导入拦不住」的坏数据就进了库。
 * 因此校验只有这一处，导入用例直接复用。
 *
 * message 里带字段名：`EntryRepositoryException` 的文案映射（`:app` 的 `invalidMessage`）
 * 依赖它把内部原因翻成人话，改动前先看 `UserMessage.kt`。
 */
object EntryValidation {

    const val TITLE_MAX = 100

    fun issues(
        title: String,
        username: String,
        password: CharArray,
        url: String?,
    ): List<EntryIssue> {
        val issues = mutableListOf<EntryIssue>()
        if (title.trim().isEmpty() || title.trim().length > TITLE_MAX) {
            issues += EntryIssue("title", "length must be 1..$TITLE_MAX")
        }
        // v1.1.2：用户名与名称、密码同为必填（此前允许为空）。
        if (username.isBlank()) {
            issues += EntryIssue("username", "username must not be empty")
        }
        if (password.isEmpty()) {
            issues += EntryIssue("password", "must not be empty")
        }
        val trimmedUrl = url?.trim().orEmpty()
        if (trimmedUrl.isNotEmpty() && !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            issues += EntryIssue("url", "must start with http:// or https://")
        }
        return issues
    }

    /** 便捷重载：校验一个待写入的输入。 */
    fun issues(input: EntryInput): List<EntryIssue> = issues(input.title, input.username, input.password, input.url)
}
