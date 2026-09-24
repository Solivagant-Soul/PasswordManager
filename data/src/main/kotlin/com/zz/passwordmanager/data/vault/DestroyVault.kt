package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.header.VaultHeaderStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 清除并重建密码库（G-B，2026-09-24 用户拍板「清除重建入口」）。
 *
 * **破坏性操作**：删除 Header 与 Body 数据库，全部条目永久丢失、无云备份可恢复。
 * 本用例只负责「删」，**确认责任在 UI 层**（须二次确认并明示数据丢失）。
 *
 * 与 [ResetVault]（密保找回重置主密码）的区别：本用例**不保留数据**，
 * 用于 Header 已损坏（`HeaderIntegrityException`）、用户确认放弃全部数据时自解。
 *
 * 幂等：Header 与数据库不存在时同样返回成功（用户可能重复点击）。
 */
class DestroyVault(
    private val headerStore: VaultHeaderStore,
    private val dbProvider: VaultDatabaseProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend operator fun invoke(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dbProvider.delete()
            headerStore.delete()
        }
    }
}
