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
}
