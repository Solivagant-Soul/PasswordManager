package com.zz.passwordmanager.domain.preferences

import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.password.GeneratorOptions
import kotlinx.coroutines.flow.Flow

/**
 * 用户偏好端口（T39/FR-16）。实现在 :data（DataStore）。
 *
 * 暴露为 `Flow` 而非一次性读取：设置改动要立即影响自动锁定行为与生成器初值，
 * 订阅式读取天然做到「改了立刻生效」。
 */
interface UserPreferences {
    val autoLockDuration: Flow<AutoLockDuration>
    val defaultPasswordLength: Flow<Int>

    /** v2.1/D4：生成器选项（编辑页与生成器页共用）。 */
    val generatorOptions: Flow<GeneratorOptions>

    /**
     * v1.1.3：是否允许截屏/录屏（`FLAG_SECURE` 的开关）。
     *
     * **默认 false**：即默认保持「不可截屏」。反过来默认 true 会在老用户升级后
     * 静默削弱既有保护，而用户并没有主动要求过。
     *
     * 作用域是**整个 App** 而非某一屏——`FLAG_SECURE` 是 window 级标志，本项目
     * 只有一个 activity，无法按页开关。设置页文案必须把这一点讲明。
     */
    val allowScreenshots: Flow<Boolean>

    suspend fun setAutoLockDuration(duration: AutoLockDuration)
    suspend fun setDefaultPasswordLength(length: Int)
    suspend fun setGeneratorOptions(options: GeneratorOptions)
    suspend fun setAllowScreenshots(allow: Boolean)
}
