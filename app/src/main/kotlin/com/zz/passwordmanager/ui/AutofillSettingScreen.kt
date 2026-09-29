package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.autofill.AutofillAvailability
import com.zz.passwordmanager.ui.components.TopBar

/**
 * 「自动填充」设置子页（FR-6，v1.2.0）。
 *
 * 单独成文件而不是塞进 [SettingsSubPages]：那一摞子页已经 361 行，再加就顶到单文件上限；
 * 且本页有独立的「读系统状态 + 跳外部 Activity」职责，与其它子页的纯本地偏好不同源。
 *
 * ## 为什么必须跳系统设置
 *
 * 填充服务意味着「在别的 App 里往表单塞凭据」，这是系统划给用户的权限，
 * 应用无权自启自举。因此本页能做的只有：如实报告开没开、解释开了会怎样、把用户送过去。
 * 没开就说没开，不做「点了没反应」。
 */
@Composable
fun AutofillSettingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 每次进入本页都重读一次：从系统设置跳回来时 Activity 会被重建，
    // 状态自然是最新的；页内再点「重新检测」也能立刻反映用户在别处的改动。
    var enabled by remember { mutableStateOf(AutofillAvailability.isServiceEnabled(context)) }
    var openFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enabled = AutofillAvailability.isServiceEnabled(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(title = "自动填充", onBack = onBack)

        Text(
            text = if (enabled) "已开启：其他 App 的登录框里会出现本密码库的建议" else "未开启",
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            "开启步骤（应用无法代你开启，这是系统的权限边界）：",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(STEP_TEXT, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { openFailed = !AutofillAvailability.requestEnable(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("去系统设置开启") }

        if (!enabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openFailed = !AutofillAvailability.openAutofillSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开自动填充设置页") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { enabled = AutofillAvailability.isServiceEnabled(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重新检测") }

        // 照实说清能力边界：两参版 onFillRequest 已从 compileSdk 35 的桩里移除，
        // 本服务只能覆盖 API 31+ 的三参入口。与其让用户以为「开了却不出建议」是 bug，
        // 不如在这里把话说死。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Spacer(Modifier.height(12.dp))
            Text(
                "本机系统为 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），" +
                    "低于本服务支持的最低版本 Android 12（API 31）：可以开启，但不会弹出填充建议。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (openFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "这台设备打不开系统自动填充设置页，请手动到「设置 → 系统 → 自动填充」里选择「密码管理」。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("开启后会发生什么", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            SECURITY_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val STEP_TEXT = """
    1. 点上方按钮，跳转到系统「自动填充」页；
    2. 选择「密码管理」为本机自动填充服务；
    3. 回到本页点「重新检测」，状态会变成「已开启」。
""".trimIndent()

private val SECURITY_TEXT = """
    · 每次填充都要先输入主密码，解锁只对这一次有效，用完立刻失效；
    · 不会因为 App 已解锁就免验证——自动锁定（1 分钟）不覆盖填充路径；
    · 输错主密码 5 次同样锁定 1 分钟，与 App 内解锁共用同一套限流；
    · 开启前请先关闭允许截屏：部分厂商 ROM 上截图保护与系统填充弹窗有冲突，
      表现为填充面板不弹出或弹出后空白。
""".trimIndent()
