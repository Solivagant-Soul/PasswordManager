package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.domain.backup.BackupCodec

/**
 * 格式 id → 编解码器。这是**唯一**知道「本 App 有哪些备份格式」的地方。
 *
 * 容器帧里写着 `codecId`，但容器自己不查这张表——查表发生在用例层
 * （`ImportBackup`）。把「登记」与「容器」分开，正是为了让新增格式
 * 不必碰容器（技术方案 §9.2）。
 *
 * 未知 id 一律拒绝，绝不「猜一个格式试着解」：把 CSV 字节喂给 JSON 解析器
 * 得到的错误信息毫无意义，而猜对了则可能写入错误数据。
 */
class BackupCodecRegistry(codecs: List<BackupCodec>) {

    private val byId: Map<String, BackupCodec> = codecs.associateBy { it.id }

    init {
        require(byId.size == codecs.size) { "duplicate backup codec id" }
    }

    operator fun get(id: String): BackupCodec? = byId[id]

    val default: BackupCodec get() = requireNotNull(byId[JsonBackupCodec().id]) {
        "default codec (${JsonBackupCodec().id}) must be registered"
    }
}
