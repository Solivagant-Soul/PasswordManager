package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.autolock.AutoLockPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话状态机（T12）：VaultState 单一事实来源；DEK 生命周期归它管（NFR-S4）。
 *
 * - [lock]：清 DEK → Locked（后台/手动/超时统一入口）；
 * - [onBackground]/[onForeground]：自动锁定触发（FR-8，AutoLockPolicy 各档，T08）；
 * - [requireUnlocked]：所有内容访问的门禁（P-01/P-06，BR-7）。
 */
class VaultSessionManager {

    private val stateFlow = MutableStateFlow<VaultState>(VaultState.Locked)
    val state: StateFlow<VaultState> = stateFlow.asStateFlow()

    private var backgroundedAtMs: Long? = null

    /** 解锁成功后进入解锁态（由 UnlockVault 的结果调用）。 */
    fun begin(session: Session) {
        stateFlow.value = VaultState.Unlocked(session)
    }

    /** 锁定：清 DEK 并回到锁定态；幂等。 */
    fun lock() {
        val current = stateFlow.value
        if (current is VaultState.Unlocked) current.session.clear()
        stateFlow.value = VaultState.Locked
    }

    /** 切到后台：仅解锁态记录时间（锁定态切换是 no-op）。 */
    fun onBackground(atEpochMs: Long) {
        if (stateFlow.value is VaultState.Unlocked) backgroundedAtMs = atEpochMs
    }

    /** 回到前台：按档位判定是否自动锁定（T08 各档）。 */
    fun onForeground(atEpochMs: Long, duration: AutoLockDuration) {
        val backgroundedAt = backgroundedAtMs ?: return
        backgroundedAtMs = null
        if (stateFlow.value is VaultState.Unlocked &&
            AutoLockPolicy.shouldLock(backgroundedAt, atEpochMs, duration)
        ) {
            lock()
        }
    }

    /** 门禁：锁定态访问内容即抛（P-01/P-06，BR-7）。 */
    fun requireUnlocked(): Session =
        (stateFlow.value as? VaultState.Unlocked)?.session ?: throw VaultLockedException()
}
