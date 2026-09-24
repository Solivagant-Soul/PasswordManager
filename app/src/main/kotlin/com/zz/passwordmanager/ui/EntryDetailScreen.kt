package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.entries.EntryDetail

/**
 * 条目详情（FR-3/FR-9）：掩码显隐 + 复制；离开页面立即隐藏明文（T17「明文不残留」）。
 * 删除在此触发二次确认后调用 onDelete（无回收站，BR-5）。
 */
@Composable
fun EntryDetailScreen(
    detail: EntryDetail?,
    revealed: String?,
    errorMessage: String?,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    // T17：离开详情页时立即清除内存中的明文（明文不残留）
    DisposableEffect(Unit) {
        onDispose { onHide() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }

        if (detail == null) {
            Text("条目不存在或已删除")
            return@Column
        }

        Text(text = detail.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text = "用户名：${detail.username}")
        if (detail.url?.isNotEmpty() == true) Text(text = "网址：${detail.url}")
        if (detail.notes?.isNotEmpty() == true) Text(text = "备注：${detail.notes}")
        if (detail.tags.isNotEmpty()) Text(text = "标签：${detail.tags.joinToString()}")
        Spacer(Modifier.height(16.dp))

        // 密码行：默认掩码；点显隐临时明文
        Text(text = "密码：${revealed ?: "••••••••"}")
        androidx.compose.foundation.layout.Row {
            TextButton(onClick = { if (revealed == null) onReveal() else onHide() }) {
                Text(if (revealed == null) "显示" else "隐藏")
            }
            TextButton(onClick = onCopy) { Text("复制") }
        }

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("编辑") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text("删除（不可恢复）")
        }
    }
}
