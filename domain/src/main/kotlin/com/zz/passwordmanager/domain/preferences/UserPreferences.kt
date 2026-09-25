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

    suspend fun setAutoLockDuration(duration: AutoLockDuration)
    suspend fun setDefaultPasswordLength(length: Int)
    suspend fun setGeneratorOptions(options: GeneratorOptions)
}
