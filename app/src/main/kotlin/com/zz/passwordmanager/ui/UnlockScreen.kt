package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 初始化/解锁一体屏（FR-1/FR-2，M2 最小实现）：
 * 未初始化 → 主密码 + 确认 + 3 道密保；已初始化 → 主密码解锁。
 */
@Composable
fun UnlockScreen(
    state: VaultUiState,
    errorMessage: String?,
    onInitialize: (UnlockFormData) -> Unit,
    onUnlock: (String) -> Unit,
) {
    var master by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var q1 by remember { mutableStateOf("") }
    var a1 by remember { mutableStateOf("") }
    var q2 by remember { mutableStateOf("") }
    var a2 by remember { mutableStateOf("") }
    var q3 by remember { mutableStateOf("") }
    var a3 by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (state.initialized) "解锁密码库" else "创建密码库",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = master,
            onValueChange = { master = it },
            label = { Text("主密码（≥8 位，两类字符）") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!state.initialized) {
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("确认主密码") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = q1, onValueChange = { q1 = it }, label = { Text("密保问题 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = a1, onValueChange = { a1 = it }, label = { Text("答案 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = q2, onValueChange = { q2 = it }, label = { Text("密保问题 2") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = a2, onValueChange = { a2 = it }, label = { Text("答案 2") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = q3, onValueChange = { q3 = it }, label = { Text("密保问题 3") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = a3, onValueChange = { a3 = it }, label = { Text("答案 3") }, modifier = Modifier.fillMaxWidth())
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (state.initialized) onUnlock(master)
                else onInitialize(
                    UnlockFormData(
                        masterPassword = master,
                        confirm = confirm,
                        questions = listOf(q1 to a1, q2 to a2, q3 to a3),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.initialized) "解锁" else "创建")
        }
    }
}
