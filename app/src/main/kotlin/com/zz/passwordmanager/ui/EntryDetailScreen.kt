package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryType

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
    onToggleFavorite: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // T17：离开详情页时立即清除内存中的明文（明文不残留）
    DisposableEffect(Unit) {
        onDispose { onHide() }
    }
    // BR-5 无回收站，删除不可恢复 → 必须二次确认（实机走查发现此处曾直接删除）
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }

        if (detail == null) {
            Text("条目不存在或已删除")
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = detail.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // FR-5 收藏：详情页可直接切换，不必回列表
            TextButton(onClick = { onToggleFavorite(!detail.favorite) }) {
                Text(if (detail.favorite) "★ 已收藏" else "☆ 收藏")
            }
        }
        Spacer(Modifier.height(8.dp))
        // FR-15：账号/银行卡/WiFi/证件，副标题的掩码策略不同
        val subtitle = detail.type.subtitle(detail.username)
        if (subtitle.isNotBlank()) {
            Text(
                text = if (detail.type == EntryType.BANK_CARD || detail.type == EntryType.DOCUMENT) {
                    "${detail.type.displayName}：$subtitle"
                } else {
                    "${detail.type.displayName}：$subtitle"
                },
            )
        }
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
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("删除（不可恢复）")
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除条目") },
            text = {
                Text("将永久删除「${detail?.title ?: ""}」及其全部内容。此操作不可恢复，也没有回收站。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
