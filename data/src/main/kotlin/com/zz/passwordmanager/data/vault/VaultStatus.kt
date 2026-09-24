package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.header.VaultHeaderStore

/** 密码库状态探测：是否已初始化（Header 存在且可读）。 */
class VaultStatus(private val headerStore: VaultHeaderStore) {

    /**
     * Header 损坏（G-B）按「已初始化」处理——走解锁路径并让异常上抛（宁严勿松），
     * 不得误入初始化流程覆盖既有库。
     */
    fun isInitialized(): Boolean = runCatching { headerStore.read() }.fold({ it != null }, { true })

    /**
     * 生物识别快捷解锁是否已开启。
     *
     * 只回布尔值、**不把整个 Header 泄给 :app**（Header 含盐与被包裹的 DEK，
     * 即便加密也不该离开数据层）。读取失败一律按「未开启」处理。
     */
    fun isBiometricEnabled(): Boolean =
        runCatching { headerStore.read()?.biometricEnabled == true }.getOrDefault(false)
}
