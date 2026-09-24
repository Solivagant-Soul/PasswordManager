package com.zz.passwordmanager

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.zz.passwordmanager.ui.AppNav
import com.zz.passwordmanager.ui.VaultViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * M2 宿主：Hilt + Compose 导航（列表/详情/编辑/解锁）。
 *
 * **V1-1 自动锁定接线**：覆写 [onStop]/[onStart] 驱动 [VaultViewModel] 的后台/前台回调，
 * 超出 1 分钟未回到前台即自动锁定（FR-8）。
 *
 * 基类是 [FragmentActivity] 而非 ComponentActivity：`BiometricPrompt` 的构造函数
 * 硬要求 FragmentActivity（V1-4 生物识别）。`setContent` 与 `@AndroidEntryPoint` 均不受影响。
 *
 * 用 `ViewModelProvider(this)` 而非 `by viewModels()`：后者是 `activity-ktx` 的扩展；
 * 而 @AndroidEntryPoint 已把 Hilt 工厂装为默认工厂，故取到的实例与 AppNav 内
 * `viewModel()` 是同一个（同 ViewModelStore）。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by lazy {
        ViewModelProvider(this)[VaultViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防后台截图/录屏：密码管理器内容不进最近任务缩略图（NFR-S，v1 取最省的一行实现）
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme {
                AppNav(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }
}
