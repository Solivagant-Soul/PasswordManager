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
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.password.PasswordGenerator

/**
 * 新增/编辑表单（FR-3/T16）：必填校验（标题/密码）在客户端先行提示，
 * 服务级校验错误经 errorMessage 展示。
 */
@Composable
fun EntryEditScreen(
    existing: com.zz.passwordmanager.domain.entries.EntryDetail?,
    errorMessage: String?,
    onSave: (EntryInput) -> Unit,
    onBack: () -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf("") } // 编辑时不回填明文（明文不残留，T17）
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString() ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showGenerator by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text(
            text = if (existing == null) "新增条目" else "编辑条目",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("标题（必填）") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(if (existing == null) "密码（必填）" else "新密码（必填）") },
            modifier = Modifier.fillMaxWidth(),
        )
        // FR-4：生成器一键回填到密码框
        TextButton(onClick = { showGenerator = true }) { Text("⚡ 生成强密码") }
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("网址（http:// 或 https://）") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it },
            label = { Text("备注") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tags, onValueChange = { tags = it },
            label = { Text("标签（逗号分隔，最多 20 个）") }, modifier = Modifier.fillMaxWidth(),
        )

        (localError ?: errorMessage)?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val issues = mutableListOf<String>()
                if (title.trim().isEmpty()) issues += "标题必填"
                if (password.isEmpty()) issues += "密码必填"
                if (issues.isNotEmpty()) {
                    localError = issues.joinToString("；")
                    return@Button
                }
                localError = null
                onSave(
                    EntryInput(
                        title = title,
                        username = username,
                        password = password.toCharArray(),
                        url = url.trim().ifEmpty { null },
                        notes = notes.ifEmpty { null },
                        categoryId = existing?.categoryId,
                        tags = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
    }

    if (showGenerator) {
        GeneratorDialog(
            onDismiss = { showGenerator = false },
            onGenerate = { rules ->
                // 领域层是纯函数、不抛异常；失败已由弹窗在校验阶段拦下，此处兜底不生成
                PasswordGenerator.generate(rules).onSuccess { password = it.concatToString() }
                showGenerator = false
            },
        )
    }
}
