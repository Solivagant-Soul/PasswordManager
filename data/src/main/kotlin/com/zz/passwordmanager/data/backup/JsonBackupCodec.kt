package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.domain.backup.BackupCodec
import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.backup.BackupRecord
import com.zz.passwordmanager.domain.backup.MalformedBackupException
import com.zz.passwordmanager.domain.backup.UnsupportedBackupVersionException
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * 本应用自有格式的 JSON 编解码器（D5：自定义加密 JSON，不做 KDBX）。
 *
 * 这是容器**内侧**的一个可替换件。CSV codec（技术方案 §9.2 提到的二期方向）
 * 会与它并列登记，`BackupContainer` 无需改动。
 *
 * ### 敏感字段的编码方式
 * 口令与 TOTP 密钥不按 JSON 字符串直出，而是走 [SecretCharsSerializer]：
 * CharArray → UTF-8 字节 → **Base64**。理由是**消除明文在堆上的副本**——
 * kotlinx 的 String 序列化器必然先造一个 `String`（不可清零），
 * 一次导出 5000 条就等于 5000 份明文排队等 GC。Base64 的密文本来就被
 * AES-GCM 罩在文件里，不存在额外的机密性损失。
 *
 * ### 版本策略
 * [VERSION] 只在**不兼容**变更时递增；新增可选字段不算。解码侧
 * `ignoreUnknownKeys = true` 让新版本写出的文件能被旧版本读掉能认的字段，
 * 而 `version` 字段负责挡住真正读不懂的那一类。两条一起用，缺一不可：
 * 只认版本号会把「多了一个我没用的字段」也判成不兼容，太脆；
 * 只认字段名则在有人改了密码字段含义时静默读出错数据。
 */
class JsonBackupCodec : BackupCodec {

    override val id: String = ID

    @OptIn(ExperimentalSerializationApi::class)
    override fun encode(payload: BackupPayload): ByteArray {
        val document = BackupDocument(
            format = FORMAT,
            version = VERSION,
            createdAt = System.currentTimeMillis(),
            categories = payload.categories.map { CategoryDto(it.id, it.name) },
            entries = payload.records.map { it.toDto() },
        )
        // 走流而不是 encodeToString：后者会额外造一份与整个文档等大的明文 String
        val out = ByteArrayOutputStream()
        JSON.encodeToStream(document, out)
        return out.toByteArray()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decode(bytes: ByteArray): BackupPayload {
        val document = try {
            JSON.decodeFromStream<BackupDocument>(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            // 坏 JSON / 类型不对 / 意外 EOF 一律归为「内容格式不合法」——
            // 它们对用户是同一件事：这个文件不是可用的备份
            if (e is OutOfMemoryError) throw e
            throw MalformedBackupException("not a valid $ID document", e)
        }
        if (document.format != FORMAT) {
            throw MalformedBackupException("unexpected format tag: ${document.format}")
        }
        if (document.version != VERSION) {
            throw UnsupportedBackupVersionException(document.version)
        }
        return BackupPayload(
            records = document.entries.map { it.toRecord() },
            categories = document.categories.map { Category(it.id, it.name) },
        )
    }

    private companion object {
        const val ID = "pmw-json"
        const val FORMAT = "pmw-backup"
        const val VERSION = 1

        /**
         * `encodeDefaults = true`：`favorite`/`totpSecret` 这类有默认值的字段显式写出，
         * 免得「旧文件缺字段」与「新文件字段为空」在阅读时长得一样。
         * `ignoreUnknownKeys = true`：见 KDoc 的版本策略。
         */
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * CharArray 的 Base64(UTF-8) 序列化器——见 `JsonBackupCodec` KDoc 的理由。
 *
 * 描述符声明为 `STRING`（而非字节数组）：JSON 里就是一个字符串字面量，
 * 只不过内容是 Base64 而不是原文。这样即便有人把容器密钥去掉、
 * 拿到未加密的明文 JSON，看到的也不是密码。
 */
internal object SecretCharsSerializer : KSerializer<CharArray> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("pmw:base64-utf8", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CharArray) {
        val bytes = Utf8.encode(value)
        try {
            encoder.encodeString(Base64.getEncoder().encodeToString(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    override fun deserialize(decoder: Decoder): CharArray {
        val bytes = try {
            Base64.getDecoder().decode(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw MalformedBackupException("secret field is not valid base64", e)
        }
        return try {
            Utf8.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}

@Serializable
private data class BackupDocument(
    val format: String,
    val version: Int,
    val createdAt: Long,
    val categories: List<CategoryDto> = emptyList(),
    val entries: List<EntryDto> = emptyList(),
)

@Serializable
private data class CategoryDto(val id: String, val name: String)

@Serializable
private data class EntryDto(
    val id: String,
    val title: String,
    val username: String,
    @Serializable(with = SecretCharsSerializer::class) val password: CharArray,
    val url: String? = null,
    val notes: String? = null,
    val categoryId: String? = null,
    @Serializable(with = SecretCharsSerializer::class) val totpSecret: CharArray? = null,
    val favorite: Boolean = false,
    val type: String = "ACCOUNT",
    val createdAt: Long,
    val updatedAt: Long,
)

private fun BackupRecord.toDto() = EntryDto(
    id = id,
    title = title,
    username = username,
    password = password,
    url = url,
    notes = notes,
    categoryId = categoryId,
    totpSecret = totpSecret,
    favorite = favorite,
    // 存枚举名而非序号：序号会随枚举插入而整体位移，跨版本读回来就全错了
    type = type.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun EntryDto.toRecord() = BackupRecord(
    id = id,
    title = title,
    username = username,
    password = password,
    url = url,
    notes = notes,
    categoryId = categoryId,
    totpSecret = totpSecret,
    favorite = favorite,
    // 宽松解析：坏值回退 ACCOUNT，与 `EntryType.from` 同一策略
    type = EntryType.from(type),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
