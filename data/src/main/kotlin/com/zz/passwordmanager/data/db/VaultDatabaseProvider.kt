package com.zz.passwordmanager.data.db

import android.content.Context

/**
 * 加密库惰性提供者（M2 架构衔接）：解锁会话给出 DEK 后才创建/打开库。
 * 锁定只清 Session DEK；已打开的连接在 MVP 中保持（深层清理列评审 G-H 待办）。
 */
class VaultDatabaseProvider(private val context: Context) {

    private var database: VaultDatabase? = null

    /** 首次以 DEK 创建，之后返回同一实例（进程内单连接）。 */
    fun get(dek: ByteArray): VaultDatabase =
        database ?: VaultDatabase.create(context, dek).also { database = it }

    /**
     * 关闭并删除 Body 数据库（G-B「清除重建」）：先关连接再由 Context 删除文件，
     * 否则 SQLCipher 的 `-wal`/`-shm` 残留。删除后下次 [get] 会以新 DEK 重新建库。
     */
    fun delete() {
        database?.close()
        database = null
        context.deleteDatabase(DATABASE_NAME)
    }

    private companion object {
        const val DATABASE_NAME = "vault.db" // 与 VaultDatabase.create 的默认名保持一致
    }
}
