package com.zz.passwordmanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** 应用入口：T01 仅挂载 Hilt 依赖容器；后续初始化随任务追加。 */
@HiltAndroidApp
class AppApplication : Application()
