package com.zz.passwordmanager

import android.app.Application
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口：挂载 Hilt 依赖容器（M2 起 DI 接线见 di/AppModule.kt）。
 *
 * 额外暴露 [biometricDekStore]：`BiometricPrompt` 的编排发生在组合层，
 * 而组合层拿不到 Hilt 的注入点（既没有 Activity 也没有 ViewModel 可拿）。
 * 字段注入是最短路径——单例、进程内唯一，且不引入 EntryPoint 样板。
 */
@HiltAndroidApp
class AppApplication : Application() {

    @Inject
    lateinit var biometricDekStore: BiometricDekStore
}
