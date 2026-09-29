package com.zz.passwordmanager.domain.backup

/**
 * 备份**内容格式**编解码器（技术方案 §9.2 记录的接缝）。
 *
 * 容器（`BackupContainer`，在 `:data`）只负责「打包 → AES-GCM → 原子落盘」与
 * 「解密 → 校验 → 原子替换」，它搬的是**不透明的字节**，从不见到本接口。
 * 容器帧里带一个 `id` 字符串，仅作为路由标签——容器自己不解释、不认识任何一种格式。
 *
 * 因此新增一种备份格式（技术方案 §9.2 提到的 CSV）的工作量是：
 * 写一个本接口的实现 + 在 `BackupCodecRegistry` 里登记，
 * **容器文件一个字都不用动**。
 */
interface BackupCodec {

    /**
     * 写入容器帧的稳定标识（ASCII，建议形如 `pmw-json`）。
     *
     * 一经发布就不得改动：改了它，此前发出的备份文件在导入时会被判为「未知格式」。
     */
    val id: String

    /** 编码为容器内层的字节。实现需在返回前清零自己造出的敏感中间量。 */
    fun encode(payload: BackupPayload): ByteArray

    /**
     * 从容器解出的字节还原内容。
     *
     * @throws UnsupportedBackupVersionException 格式版本不受支持（不兼容变更）
     * @throws MalformedBackupException 内容不是本格式的合法表示
     */
    fun decode(bytes: ByteArray): BackupPayload
}

/** 备份内容格式的版本不受支持。独立于认证失败：解密成功但版本读不懂。 */
class UnsupportedBackupVersionException(val formatVersion: Int) :
    Exception("unsupported backup format version: $formatVersion")

/** 内容不是本格式的合法表示（截断、坏 JSON、字段类型不对……）。 */
class MalformedBackupException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
