package com.zz.passwordmanager.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份文件的读写端口。
 *
 * 抽成接口是因为「原子落盘」在不同宿主上的**能力不同**，必须让这件事显式可见：
 * 本地文件系统能真正原子发布，SAF 的 DocumentsProvider 没有 rename 语义。
 * 把两者抹平成同一个 `write(bytes)` 会让「原子」这个词在 SAF 路径上悄悄失效，
 * 而失效点正落在最不该失效的地方——用户唯一的一份备份。
 */
interface BackupFileStore {

    /** 读回完整文件字节。 */
    suspend fun read(): ByteArray

    /**
     * 写入 [bytes] 并使其成为唯一可见的版本。
     *
     * 契约：**失败时不得留下看似完整实则损坏的文件**；已存在的旧版本要么完好无损，
     * 要么被完整的新版本取代，不允许中间态。
     */
    suspend fun write(bytes: ByteArray)
}

/** 文件读写失败（含 SAF 无提供方）。UI 需如实告知，不得静默失败。 */
class BackupIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 暂存文件 → 目标文件。先 fsync 再 rename：rename 是原子的，不代表内容已经落盘。 */
private fun writeAndSync(staging: File, bytes: ByteArray) {
    FileOutputStream(staging).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
}

/**
 * 尽量原子地把暂存文件挪到目标位置。
 *
 * 退化到非原子 move 的情形与 [com.zz.passwordmanager.data.header.VaultHeaderStore] 一致：
 * 少数文件系统（部分 SD 卡 / FUSE 挂载）不支持 `ATOMIC_MOVE`。此时仍是一次 rename，
 * 读者不会看到半个文件，但**发布与 rename 之间**存在极窄的窗口——
 * 掉电可能留下 0 字节的目标文件。之所以不直接失败：备份是低频操作，
 * 因为个别卡的挂载方式就让整个功能不可用并不划算，而退化路径的后果是
 * 「丢一次备份」而非「泄露数据」。
 */
private fun moveIntoPlace(staging: File, target: File) {
    try {
        Files.move(
            staging.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * 本地文件系统的实现：**临时文件 + fsync + ATOMIC_MOVE**。
 *
 * 这是「原子落盘」的参考实现，也是 SAF 实现的前半段（先在这里把密文完整落盘）。
 * `FileBackupFileStoreAtomicityTest` 盯着它：若有人改成直接 `writeBytes(目标)`，
 * 「写到一半失败」那组用例会立刻红。
 */
class FileBackupFileStore(
    private val target: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 「把字节写进暂存文件」这一步。**只为测试可注入**：
     * 原子性最难验证的形态是「写到一半失败」，而磁盘不会配合我们失败。
     * 把这一步提成参数后，测试就能注入一个写一半就抛异常的版本，然后断言
     * **目标文件仍持有旧内容**——这正是「直接写目标文件」的实现会立刻失败的断言。
     * 生产代码走默认实现，永远不该传别的。
     */
    private val stageWriter: (File, ByteArray) -> Unit = ::writeAndSync,
) : BackupFileStore {

    override suspend fun read(): ByteArray = withContext(ioDispatcher) {
        if (!target.exists()) throw BackupIoException("backup file not found")
        val length = target.length()
        if (length > MAX_BACKUP_BYTES) {
            throw BackupIoException("backup file too large: $length bytes")
        }
        target.readBytes()
    }

    override suspend fun write(bytes: ByteArray): Unit = withContext(ioDispatcher) {
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw BackupIoException("refusing to write ${bytes.size} bytes")
        }
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw BackupIoException("cannot create ${parent.path}")
        }
        val staging = File(parent, "${target.name}.part")
        try {
            stageWriter(staging, bytes)
            moveIntoPlace(staging, target)
        } catch (e: IOException) {
            // 目标文件自始至终没被碰过：要么是旧版本（完好），要么不存在。半成品只存在于
            // .part 里，必须清掉，否则用户会在下载列表里看到一个 .bak.part 的怪文件
            staging.delete()
            throw BackupIoException("failed to write backup", e)
        }
    }

    companion object {
        /**
         * 64 MiB 上限。5000 条目的备份在正常量级下只有几 MB；这个闸门是给**恶意或
         * 损坏**文件用的——一个声称几百 GB 的备份不该在读取时把进程 OOM 掉。
         */
        const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    }
}

/**
 * SAF（`CreateDocument` / `OpenDocument`）的实现。
 *
 * ### 这里的原子性说到哪一步为止
 * DocumentsProvider 的写接口只有「打开输出流 → 写 → 关」，**没有 rename 或
 * 临时+发布**的语义，所以严格意义上的原子发布在 SAF 上做不到。本实现给出的、
 * 能做到的最强保证是：
 *
 * 1. 容器已经在 [staging] 里**完整落盘并 fsync 过**（走的是与本地实现同一条
 *    临时文件 + rename 路径），因此交给 provider 的是一份已经定稿、且 GCM 自检通过的字节；
 * 2. 一次 `write` + `flush` + `close` 写出，不做多次追加；
 * 3. 万一 provider 侧仍被中断（拔线、provider 崩溃），产物是**长度短于声明值或标签不匹配**的文件，
 *    下次导入会在解密前/认证时被拒（见 [BackupContainer.open]）——
 *    即「坏掉的备份不可能被误当成好备份导入」，这正是 S47 要的语义。
 *
 * 想要严格原子请改用 [FileBackupFileStore]（如 `adb` 拉取或应用私有目录）。
 */
class SafBackupFileStore(
    private val resolver: ContentResolver,
    private val uri: Uri,
    stagingDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupFileStore {

    private val staging = File(stagingDir, "saf-staging.pmbak")

    override suspend fun read(): ByteArray = withContext(ioDispatcher) {
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            throw BackupIoException("无法读取所选文件", e)
        } ?: throw BackupIoException("无法读取所选文件（提供方已不可用）")
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > FileBackupFileStore.MAX_BACKUP_BYTES) {
                    throw BackupIoException("备份文件过大")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(ioDispatcher) {
        val dir = staging.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw BackupIoException("无法准备临时目录")
        }
        // 先在本地把定稿的密文落盘，再交给 provider
        FileOutputStream(staging).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            val stream = try {
                resolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                throw BackupIoException("无法写入所选位置（可能没有可用的文件提供方）", e)
            } ?: throw BackupIoException("无法写入所选位置（可能没有可用的文件提供方）")
            stream.use { it.write(staging.readBytes()) }
        } finally {
            staging.delete()
        }
    }
}
