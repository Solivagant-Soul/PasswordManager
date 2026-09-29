package com.zz.passwordmanager

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zz.passwordmanager.domain.preferences.UserPreferences
import com.zz.passwordmanager.ui.AppNav
import com.zz.passwordmanager.ui.VaultViewModel
import com.zz.passwordmanager.ui.theme.PasswordManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    /** v1.1.3：截屏开关的来源。与 ViewModel 注入的是同一个 DataStore 单例。 */
    @Inject
    lateinit var preferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防后台截图/录屏：密码管理器内容不进最近任务缩略图（NFR-S，v1 取最省的一行实现）
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        observeScreenshotPreference()
        setContent {
            PasswordManagerTheme {
                AppNav(viewModel = viewModel)
            }
        }
    }

    /**
     * v1.1.3：按偏好增删 `FLAG_SECURE`，**含启动时的初值**。
     *
     * 机制选 `lifecycleScope + repeatOnLifecycle(STARTED)` 而不是在 Compose 里
     * `LaunchedEffect`：`FLAG_SECURE` 属于 window，不属于任何一屏的组合状态，
     * 放在 Activity 侧才与「解锁/锁定」这类 UI 态解耦；`repeatOnLifecycle` 保证
     * 退到后台即停订（后台没有窗口变化可应用），回前台重新取值；`lifecycleScope`
     * 随 `onDestroy` 取消，不泄漏。
     *
     * onCreate 里已经先把 flag 置上（fail-closed），故从 STARTED 到 DataStore 首个
     * 读回之间不存在「已放开截屏」的窗口——那几百毫秒里宁可多挡一次。
     */
    private fun observeScreenshotPreference() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferences.allowScreenshots.collect { allow ->
                    if (allow) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    }
                }
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
