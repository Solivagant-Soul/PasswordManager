package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.header.VaultHeaderStore

/** 密码库状态探测：是否已初始化（Header 存在且可读）。 */
class VaultStatus(private val headerStore: VaultHeaderStore) {

    /**
     * Header 损坏（G-B）按「已初始化」处理——走解锁路径并让异常上抛（宁严勿松），
     * 不得误入初始化流程覆盖既有库。
     */
    fun isInitialized(): Boolean = runCatching { headerStore.read() }.fold({ it != null }, { true })
}
