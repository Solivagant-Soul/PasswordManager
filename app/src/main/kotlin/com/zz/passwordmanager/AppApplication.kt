package com.zz.passwordmanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** 应用入口：挂载 Hilt 依赖容器（M2 起 DI 接线见 di/AppModule.kt）。 */
@HiltAndroidApp
class AppApplication : Application()
