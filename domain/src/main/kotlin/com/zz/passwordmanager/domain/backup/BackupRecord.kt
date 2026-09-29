package com.zz.passwordmanager.domain.backup

import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryType

/**
 * 备份中的一条条目（FR-7）。**格式中立**：它不含任何序列化痕迹，
 * 既是编解码器的输入输出，也是不依赖 Room 的「整库替换」载荷。
 *
 * [password]/[totpSecret] 是 [CharArray] 而非 String，与 `EntryInput` 同理（JVM 无法清零
 * String 副本，敏感值只以可清零的容器在内存里流动）。用完必须调 [clear]。
 */
data class BackupRecord(
    val id: String,
    val title: String,
    val username: String,
    val password: CharArray,
    val url: String?,
    val notes: String?,
    val categoryId: String?,
    val totpSecret: CharArray?,
    val favorite: Boolean,
    val type: EntryType,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 清零敏感字段。幂等，可在 finally 里无条件调用。 */
    fun clear() {
        password.fill(ZERO)
        totpSecret?.fill(ZERO)
    }

    private companion object {
        /** 填 NUL 而不是空格：与项目里 `ByteArray.fill(0)` 的清零约定一致，也便于测试断言。 */
        val ZERO: Char = Char(0)
    }
}

/**
 * 一份备份的完整内容：条目 + 分类。
 *
 * 分类一并带走：`entries.categoryId` **没有外键**（见 `CategoryRepository` 的说明），
 * 只搬条目会留下一批指向已不存在分类的孤儿引用，按分类筛选时行为不可预期。
 *
 * [clear] 逐条清零，不设「已清零」标志位——本对象是短生命周期的，
 * 调用方在 `finally` 里调一次即可，标志位只会诱使人漏调。
 */
data class BackupPayload(
    val records: List<BackupRecord>,
    val categories: List<Category>,
) {
    val entryCount: Int get() = records.size

    fun clear() = records.forEach { it.clear() }
}
