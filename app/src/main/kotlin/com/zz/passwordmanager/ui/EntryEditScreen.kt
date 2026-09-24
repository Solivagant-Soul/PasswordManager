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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import com.zz.passwordmanager.domain.entries.EntryType

/**
 * 新增/编辑表单（FR-3/T16）：必填校验（标题/密码）在客户端先行提示，
 * 服务级校验错误经 errorMessage 展示。
 */
@Composable
fun EntryEditScreen(
    existing: com.zz.passwordmanager.domain.entries.EntryDetail?,
    categories: List<com.zz.passwordmanager.domain.entries.Category>,
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
    // FR-15 条目类型：决定列表副标题的渲染（银行卡/证件自动掩码）
    var type by remember { mutableStateOf(existing?.type ?: EntryType.ACCOUNT) }
    // FR-5 分类：单选筛选维度，与多选标签互补
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var favorite by remember { mutableStateOf(existing?.favorite ?: false) }

    // 顶栏「保存」与底部按钮共用，避免两处构造逻辑漂移
    fun currentInput(): EntryInput = EntryInput(
        title = title.trim(),
        username = username.trim(),
        password = password.toCharArray(),
        url = url.trim().ifEmpty { null },
        notes = notes.ifEmpty { null },
        categoryId = categoryId,
        tags = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
        favorite = favorite,
        type = type,
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (existing == null) "新增条目" else "编辑条目",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = title.isNotBlank() && (existing == null || password.isNotEmpty()),
                onClick = { onSave(currentInput()) },
            ) { Text("保存") }
        }
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
        // 类型（FR-15）：四种类型，chips 选一档
        Text("类型", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EntryType.entries.toList(), key = { it.name }) { t ->
                FilterChip(
                    selected = type == t,
                    onClick = { type = t },
                    label = { Text(t.displayName) },
                )
            }
        }

        // 分类（FR-5）：单选，可清空回到「未分类」
        Text("分类", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = categoryId == null,
                    onClick = { categoryId = null },
                    label = { Text("未分类") },
                )
            }
            items(categories, key = { it.id }) { category ->
                FilterChip(
                    selected = categoryId == category.id,
                    onClick = { categoryId = category.id },
                    label = { Text(category.name) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = favorite, onCheckedChange = { favorite = it })
            Text(if (favorite) "已收藏" else "收藏")
        }

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
                onSave(currentInput())
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
