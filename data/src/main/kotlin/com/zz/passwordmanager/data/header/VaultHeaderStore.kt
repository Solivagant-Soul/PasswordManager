package com.zz.passwordmanager.data.header

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Header 完整性破坏（篡改/损坏/密钥不符）——校验失败即拒绝（R11），调用方不得降级放行。 */
class HeaderIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Vault Header 存储：JSON 文件 + HMAC（方案 §4.1）。
 *
 * 独立于 DEK——未初始化/锁定状态下也可读取（开库前的引导数据，B1）。
 * 写入采用临时文件 + rename 原子替换（S2 语义），中断不留半文件。
 *
 * @param directory 私有目录（生产用 context.filesDir）；测试可传临时目录
 * @param macKey HMAC 密钥（注入以保证纯 JVM 可测；Keystore 托管见 T05/T29）
 */
class VaultHeaderStore(
    private val directory: File,
    private val macKey: ByteArray,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(directory, FILE_NAME)

    /**
     * 读取并验签。
     * @return Header；文件不存在返回 null（未初始化）
     * @throws HeaderIntegrityException 文件损坏、被篡改或 MAC 密钥不符
     */
    fun read(): VaultHeader? {
        if (!file.exists()) return null
        val stored = try {
            json.decodeFromString<StoredHeader>(file.readText())
        } catch (e: SerializationException) {
            throw HeaderIntegrityException("vault header is unreadable", e)
        }
        val mac = HeaderMac(macKey)
        if (!mac.verify(stored.data.encodeToByteArray(), stored.mac)) {
            throw HeaderIntegrityException("vault header MAC mismatch")
        }
        return json.decodeFromString<VaultHeader>(stored.data)
    }

    /** 写入：先计算 MAC，再临时文件 + 原子替换落盘。 */
    fun write(header: VaultHeader) {
        val data = json.encodeToString(header)
        val mac = HeaderMac(macKey).compute(data.encodeToByteArray())
        val stored = json.encodeToString(StoredHeader(data = data, mac = mac))

        val tmp = File(directory, "$FILE_NAME.tmp")
        tmp.writeText(stored)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("vault header write failed", e)
        }
    }

    /** 嵌套存储结构：data 为 Header JSON 文本，mac 覆盖 data 的原始字节。 */
    @kotlinx.serialization.Serializable
    private data class StoredHeader(val data: String, val mac: ByteArray)

    private companion object {
        const val FILE_NAME = "vault-header.json"
    }
}
