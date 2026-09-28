package com.zz.passwordmanager.ui

/*
 * 设置的五个子页（v2.1/§4.1）。与主页 [SettingsScreen] 分文件是为了守住单文件 400 行的上限。
 *
 * 目录名 `settings` 与包名不一致是刻意的：这五个 Composable 在 `AppNav` 中是**无限定引用**
 * （同包直呼），改包名就得同步改导航接线，而接线文件本任务不归我改。Kotlin 按包解析、
 * 不看目录，故同名包放在子目录里既满足「子页独立成文件」又不牵动导航。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.password.PasswordGenerator
import com.zz.passwordmanager.ui.components.TopBar

/**
 * 自动锁定档位的中文标签。
 *
 * 公开而非私有：v2.1 把自动锁定拆成子页后，安全中心等屏也要展示当前档位，
 * 私有的扩展会让每处各写一份中文映射而漂移。
 */
val AutoLockDuration.displayLabel: String
    get() = when (this) {
        AutoLockDuration.IMMEDIATE -> "立即"
        AutoLockDuration.S30 -> "30 秒"
        AutoLockDuration.M1 -> "1 分钟"
        AutoLockDuration.M5 -> "5 分钟"
    }

/** 自动锁定档位（FR-8/T40）。改动即时生效——档位已在 [VaultUiState] 中（F1）。 */
@Composable
fun AutoLockSettingScreen(
    current: AutoLockDuration,
    onSelect: (AutoLockDuration) -> Unit,
    onBack: () -> Unit,
) {
    // 档位只有 4 个，恒定放得下，不加滚动容器
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TopBar(title = "自动锁定", onBack = onBack)
        Text(
            "切到后台超过该时长后自动锁定",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        AutoLockDuration.entries.forEach { duration ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(duration.displayLabel, modifier = Modifier.weight(1f))
                RadioButton(
                    selected = current == duration,
                    onClick = { onSelect(duration) },
                )
            }
        }
    }
}

/** 生物识别快捷解锁（FR-2）。开启需验主密码 + 一次指纹/面容确认（P-03）。 */
@Composable
fun BiometricSettingScreen(
    enabled: Boolean,
    onToggle: (Boolean, String) -> Unit,
    onRunDiagnostics: () -> String,
    onBack: () -> Unit,
) {
    // 存 Boolean? 而非 Boolean：null = 未弹窗，兼表开关目标态与「对话框是否可见」
    var showPasswordPrompt by remember { mutableStateOf<Boolean?>(null) }
    var diagnosticReport by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TopBar(title = "生物识别解锁", onBack = onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (enabled) "已开启：可用指纹/面容快速解锁" else "未开启：每次都需输入主密码",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = { showPasswordPrompt = it })
        }
        Spacer(Modifier.height(8.dp))
        // 排障入口：开关切不过去时，先看诊断结论再决定是否需要兼容模式
        OutlinedButton(
            onClick = { diagnosticReport = onRunDiagnostics() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("生物识别诊断") }
    }

    diagnosticReport?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnosticReport = null },
            title = { Text("生物识别诊断") },
            text = { Text(report, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { diagnosticReport = null }) { Text("知道了") }
            },
        )
    }

    // P-03：开关生物识别须验证当前主密码
    showPasswordPrompt?.let { enable ->
        var password by remember(enable) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordPrompt = null },
            title = { Text(if (enable) "开启生物识别解锁" else "关闭生物识别解锁") },
            text = {
                Column {
                    Text(
                        if (enable) "请输入当前主密码以确认，随后需验证一次指纹或面容。"
                        else "请输入当前主密码以确认。"
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("当前主密码") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = {
                        onToggle(enable, password)
                        showPasswordPrompt = null
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordPrompt = null }) { Text("取消") }
            },
        )
    }
}

/** 默认密码长度（T41）。上界与 [PasswordGenerator.LENGTH_MAX] 一致（Task 2 已收窄为 32）。 */
@Composable
fun DefaultLengthSettingScreen(
    current: Int,
    onChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    // 固定 4 档内容，恒定放得下
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TopBar(title = "默认密码长度", onBack = onBack)
        Text(
            "新建条目时点「生成」所用的长度",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { onChange(current - 4) },
                enabled = current > PasswordGenerator.LENGTH_MIN,
            ) { Text("−") }
            Text(
                "$current",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = { onChange(current + 4) },
                enabled = current < PasswordGenerator.LENGTH_MAX,
            ) { Text("＋") }
        }
    }
}

/** 分类管理（FR-5/T34）。可滚动——分类多时不至于挤爆屏幕（F2 的同类修法）。 */
@Composable
fun CategorySettingScreen(
    categories: List<Category>,
    onUpsert: (String?, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var newCategory by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(title = "分类管理", onBack = onBack)
        Text(
            "分类用于列表筛选（单选），标签用于归类（多选）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        categories.forEach { category ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (editingId == category.id) {
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        label = { Text("分类名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onUpsert(category.id, editingName)
                        editingId = null; editingName = ""
                    }) { Text("保存") }
                    TextButton(onClick = { editingId = null }) { Text("取消") }
                } else {
                    Text(category.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        editingId = category.id; editingName = category.name
                    }) { Text("改名") }
                    TextButton(onClick = { onDelete(category.id) }) { Text("删除") }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newCategory,
                onValueChange = { newCategory = it },
                label = { Text("新分类名（1–30 字）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = newCategory.isNotBlank(),
                onClick = { onUpsert(null, newCategory); newCategory = "" },
            ) { Text("添加") }
        }
    }
}

/** 修改主密码（P-07）。成功后由 `onChange` 的回调负责 pop 回设置主页。 */
@Composable
fun ChangePasswordScreen(
    errorMessage: String?,
    successMessage: String?,
    onChange: (current: String, new: String, confirm: String) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    // 软键盘顶起时输入框会互相遮挡，可滚动才能滚到「修改主密码」按钮
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(title = "修改主密码", onBack = onBack)
        OutlinedTextField(
            value = current, onValueChange = { current = it },
            label = { Text("当前主密码") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = new, onValueChange = { new = it },
            label = { Text("新主密码（≥8 位，两类字符）") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it },
            label = { Text("确认新主密码") }, modifier = Modifier.fillMaxWidth(),
        )

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        successMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onChange(current, new, confirm); current = ""; new = ""; confirm = "" },
            enabled = current.isNotEmpty() && new.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("修改主密码") }
    }
}
