package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.data.vault.VaultLockedException
import com.zz.passwordmanager.domain.backup.BackupCodec
import com.zz.passwordmanager.domain.backup.BackupVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导出加密备份（FR-7 / T26）。
 *
 * 流水线：`读全库（明文） → 编码 → 加密 → 原子落盘`，
 * 每一步产出的敏感字节都在下一步开始前清零——**明文的存活期被压到最短**，
 * 这是 T17/NFR-S4 在备份场景下的直接要求（一次导出会把全库口令同时握在手里）。
 *
 * 调度器分工按项目约定：整条链路在 [ioDispatcher] 上（读库与落盘都是阻塞式重活），
 * Argon2 派生在 `BackupKeyDeriver` 内部自行切到 `Dispatchers.Default`。
 */
class ExportBackup(
    private val backupVault: BackupVault,
    private val container: BackupContainer,
    private val codec: BackupCodec,
    private val keyDeriver: BackupKeyDeriver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * @param masterPassword 用于派生备份密钥的主密码（不落盘、不入返回值）
     * @param target 已由 SAF 选定的写入目标
     * @return 成功时为写入的条目数
     */
    suspend fun export(
        masterPassword: CharArray,
        target: BackupFileStore,
    ): Result<Int> = withContext(ioDispatcher) {
        val key = keyDeriver.derive(masterPassword)
        var payload: com.zz.passwordmanager.domain.backup.BackupPayload? = null
        var plaintext: ByteArray? = null
        try {
            payload = backupVault.readAll()
            val count = payload.entryCount
            plaintext = codec.encode(payload)
            val frame = container.seal(codec.id, plaintext, key)
            target.write(frame)
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 顺序要紧：先清条目的口令，再清编解码产物，最后清密钥
            payload?.clear()
            plaintext?.fill(0)
            key.fill(0)
        }
    }
}
