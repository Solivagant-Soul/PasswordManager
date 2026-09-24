package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.autolock.AutoLockDuration

/**
 * 设置屏（v1 最小集）：P-07 修改主密码 + 主动锁定。
 * 生物识别开关在此屏（V1-4 接入）。
 */
@Composable
fun SettingsScreen(
    errorMessage: String?,
    successMessage: String?,
    biometricEnabled: Boolean,
    categories: List<com.zz.passwordmanager.domain.entries.Category>,
    autoLockDuration: AutoLockDuration,
    defaultPasswordLength: Int,
    appVersionName: String,
    onRunBiometricDiagnostics: () -> String,
    onSetAutoLockDuration: (AutoLockDuration) -> Unit,
    onSetDefaultPasswordLength: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onChangeMasterPassword: (current: String, new: String, confirm: String) -> Unit,
    onBiometricToggle: (enable: Boolean, masterPassword: String) -> Unit,
    onUpsertCategory: (String?, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showBiometricPasswordPrompt by remember { mutableStateOf<Boolean?>(null) }
    var diagnosticReport by remember { mutableStateOf<String?>(null) }
    var newCategory by remember { mutableStateOf("") }
    var editingCategoryId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("修改主密码", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = current,
            onValueChange = { current = it },
            label = { Text("当前主密码") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = new,
            onValueChange = { new = it },
            label = { Text("新主密码（≥8 位，两类字符）") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("确认新主密码") },
            modifier = Modifier.fillMaxWidth(),
        )

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        successMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onChangeMasterPassword(current, new, confirm)
                current = ""; new = ""; confirm = ""
            },
            enabled = current.isNotEmpty() && new.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("修改主密码")
        }

        // ---- FR-2 生物识别快捷解锁开关（P-03：须验主密码） ----
        Spacer(Modifier.height(24.dp))
        Text("生物识别解锁", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (biometricEnabled) "已开启：可用指纹/面容快速解锁" else "未开启：每次都需输入主密码",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = biometricEnabled,
                onCheckedChange = { showBiometricPasswordPrompt = it },
            )
        }
        // 排障入口：开关切不过去时，先看诊断结论再决定是否需要兼容模式
        TextButton(
            onClick = { diagnosticReport = onRunBiometricDiagnostics() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("生物识别诊断") }

        // ---- 自动锁定档位（T40/FR-16）----
        Spacer(Modifier.height(24.dp))
        Text("自动锁定", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AutoLockDuration.entries.forEach { duration ->
                FilterChip(
                    selected = autoLockDuration == duration,
                    onClick = { onSetAutoLockDuration(duration) },
                    label = { Text(duration.label) },
                )
            }
        }
        Text(
            "切到后台超过该时长后自动锁定",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 默认密码长度（T41/FR-16）----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("默认密码长度", modifier = Modifier.weight(1f))
            TextButton(onClick = { onSetDefaultPasswordLength(defaultPasswordLength - 4) }) { Text("−") }
            Text("$defaultPasswordLength", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onSetDefaultPasswordLength(defaultPasswordLength + 4) }) { Text("＋") }
        }

        // ---- 关于（T46）----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("关于", modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenAbout) { Text("v$appVersionName ›") }
        }

        // ---- 分类管理（T34/T35）----
        Spacer(Modifier.height(24.dp))
        Text("分类", style = MaterialTheme.typography.titleMedium)
        Text(
            "分类用于列表筛选（单选），标签用于归类（多选）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        categories.forEach { category ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (editingCategoryId == category.id) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text("分类名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onUpsertCategory(category.id, newCategory)
                        editingCategoryId = null; newCategory = ""
                    }) { Text("保存") }
                    TextButton(onClick = { editingCategoryId = null }) { Text("取消") }
                } else {
                    Text(category.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        editingCategoryId = category.id; newCategory = category.name
                    }) { Text("改名") }
                    TextButton(onClick = { onDeleteCategory(category.id) }) { Text("删除") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newCategory,
                onValueChange = { newCategory = it },
                label = { Text("新分类名（1–30 字）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = newCategory.isNotBlank(),
                onClick = { onUpsertCategory(null, newCategory); newCategory = "" },
            ) { Text("添加") }
        }
    }

    // 诊断结果
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
    showBiometricPasswordPrompt?.let { enable ->
        var password by remember(enable) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBiometricPasswordPrompt = null },
            title = { Text(if (enable) "开启生物识别解锁" else "关闭生物识别解锁") },
            text = {
                Column {
                    Text(if (enable) "请输入当前主密码以确认，随后需验证一次指纹或面容。" else "请输入当前主密码以确认。")
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
                        onBiometricToggle(enable, password)
                        showBiometricPasswordPrompt = null
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showBiometricPasswordPrompt = null }) { Text("取消") }
            },
        )
    }
}

private val AutoLockDuration.label: String
    get() = when (this) {
        AutoLockDuration.IMMEDIATE -> "立即"
        AutoLockDuration.S30 -> "30 秒"
        AutoLockDuration.M1 -> "1 分钟"
        AutoLockDuration.M5 -> "5 分钟"
    }
