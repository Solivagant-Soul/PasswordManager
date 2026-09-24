package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
 * 初始化/解锁一体屏（FR-1/FR-2，M2 最小实现）：
 * 未初始化 → 主密码 + 确认 + 3 道密保；已初始化 → 主密码解锁。
 *
 * G-B 损坏态：Header 完整性校验失败时不显示表单（数据已不可用，输密码也无用），
 * 改为提示 + 「重置密码库」入口，并经**两级确认**——破坏性操作不可一击生效。
 */
@Composable
fun UnlockScreen(
    state: VaultUiState,
    errorMessage: String?,
    onInitialize: (UnlockFormData) -> Unit,
    onUnlock: (String) -> Unit,
    onDestroyVault: () -> Unit = {},
    onBeginRecovery: () -> Unit = {},
    onRecover: (answers: List<CharArray>, newPassword: String, confirm: String) -> Unit = { _, _, _ -> },
    onCancelRecovery: () -> Unit = {},
) {
    if (state.vaultCorrupted) {
        CorruptedVaultNotice(onDestroyVault = onDestroyVault)
        return
    }
    if (state.recovering) {
        RecoveryForm(
            questions = state.recoveryQuestions,
            errorMessage = errorMessage,
            onSubmit = onRecover,
            onCancel = onCancelRecovery,
        )
        return
    }

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
        state.successMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.primary)
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

        // FR-12 找回入口：仅已初始化时可用（未初始化时无密保可答）
        if (state.initialized) {
            TextButton(onClick = onBeginRecovery, modifier = Modifier.fillMaxWidth()) {
                Text("忘记主密码？用密保问题找回")
            }
        }
    }
}

/**
 * FR-12 密保找回表单：答对 3 道密保问题后重设主密码（BR-6：原主密码不可取回）。
 * 展示的只有问题文本——答案从不落盘（D4）。
 */
@Composable
private fun RecoveryForm(
    questions: List<String>,
    errorMessage: String?,
    onSubmit: (List<CharArray>, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val answers = remember { List(3) { mutableStateOf("") } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("找回主密码", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("回答全部 3 道密保问题后可重设主密码。原主密码无法找回。")

        Spacer(Modifier.height(16.dp))
        questions.forEachIndexed { index, question ->
            OutlinedTextField(
                value = answers[index].value,
                onValueChange = { answers[index].value = it },
                label = { Text(question) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
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

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(answers.map { it.value.toCharArray() }, newPassword, confirm) },
            enabled = answers.all { it.value.isNotBlank() } &&
                newPassword.isNotEmpty() && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重设主密码")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("返回主密码解锁")
        }
    }
}

/** G-B 损坏态：告知 + 两级确认的「重置密码库」（破坏性，全部数据永久丢失）。 */
@Composable
private fun CorruptedVaultNotice(onDestroyVault: () -> Unit) {
    var stage by remember { mutableStateOf(0) } // 0=初始提示 1=最终确认

    if (stage == 0) {
        AlertDialog(
            onDismissRequest = { stage = 0 },
            title = { Text("密码库不可用") },
            text = {
                Text(
                    "密码库数据完整性校验失败，通常是文件损坏或被意外修改。" +
                        "数据文件可能仍在设备上，但无法解密，也无法用主密码解锁。" +
                        "本应用没有云备份，无法自动恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = { stage = 1 }) { Text("重置密码库…") }
            },
            dismissButton = {
                TextButton(onClick = { stage = 0 }) { Text("退出") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { stage = 0 },
            title = { Text("确认清除全部数据？") },
            text = {
                Text(
                    "这将永久删除密码库中的全部条目，且无法恢复。" +
                        "若你曾导出过加密备份，请先退出并从备份恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = onDestroyVault) { Text("确认删除全部数据") }
            },
            dismissButton = {
                TextButton(onClick = { stage = 0 }) { Text("取消") }
            },
        )
    }
}
