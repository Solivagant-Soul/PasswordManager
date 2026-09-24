package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置屏（v1 最小集）：P-07 修改主密码 + 主动锁定。
 * 生物识别开关在此屏（V1-4 接入）。
 */
@Composable
fun SettingsScreen(
    errorMessage: String?,
    successMessage: String?,
    onChangeMasterPassword: (current: String, new: String, confirm: String) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

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
    }
}
