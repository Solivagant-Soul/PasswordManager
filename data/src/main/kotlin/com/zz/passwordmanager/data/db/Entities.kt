package com.zz.passwordmanager.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 密码条目（方案 §4.1 Body；字段约束映射需求 §6）。
 *
 * [passwordCipher]/[totpSecretCipher] 为 DEK 字段级加密后的密文（D8 纵深防御）：
 * 整库已被 SQLCipher 加密，敏感字段再加密一层。
 *
 * [titleLower]/[usernameLower]/[urlLower] 为归一化搜索列（T13，M4 大小写不敏感检索），
 * 由写入方维护；索引服务分类过滤与排序（包含式 LIKE 在 5000 条规模全表扫描可接受）。
 */
@Entity(
    tableName = "entries",
    indices = [
        Index("titleLower"),
        Index("usernameLower"),
        Index("urlLower"),
        Index("categoryId"),
    ],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val titleLower: String,
    val username: String,
    val usernameLower: String,
    val passwordCipher: ByteArray,
    val url: String?,
    val urlLower: String?,
    val notes: String?,
    val categoryId: String?,
    val totpSecretCipher: ByteArray?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 分类（1–30 字符约束在上层校验，存储层保持中性）。 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
)

/** 标签（以名称为主键，天然去重）。 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
)

/** 条目-标签多对多；单条 ≤20 的上限在上层校验。 */
@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entryId", "tagName"],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["name"],
            childColumns = ["tagName"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId"), Index("tagName")],
)
data class EntryTagEntity(
    val entryId: String,
    val tagName: String,
)
