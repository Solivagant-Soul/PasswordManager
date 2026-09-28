package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.R
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.ui.components.Avatar
import com.zz.passwordmanager.ui.components.CardSurface
import com.zz.passwordmanager.ui.components.TopBar

/**
 * 条目详情（FR-3/FR-9）：掩码显隐 + 复制；离开页面立即隐藏明文（T17「明文不残留」）。
 *
 * 版式按设计稿「条目详情」重排（v2.1）：顶栏 → 头像/标题/分类 → 信息卡片 → 底部动作区。
 * 删除留在顶栏（列表页没有删除入口，删掉它整条删除链路就断了），仍走 BR-5 的二次确认。
 */
@Composable
fun EntryDetailScreen(
    detail: EntryDetail?,
    categories: List<Category>,
    revealed: String?,
    errorMessage: String?,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    onCopyUsername: () -> Unit,
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
    // 网址由系统浏览器打开，故自行取 UriHandler：它不需要跨层状态，
    // 为此在 AppNav 里加一条回调纯属把导航层和 UI 细节焊死。
    val uriHandler = LocalUriHandler.current

    // 备注上限 2000 字、网址 2048 字，加上固定的 TOTP/标签/底部动作区，
    // 不滚动的话底部「收藏」按钮直接被推出屏幕，点了没法用。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(
            title = "条目详情",
            onBack = onBack,
            action = {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )

        if (detail == null) {
            Text("条目不存在或已删除")
            return@Column
        }

        // 头部：头像 + 标题 + 分类徽章
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(detail.title, size = 64.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                detail.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            // categoryId 是 UUID，直接印出来用户看到的是一串十六进制。
            // 查不到名字（分类已被删除、或历史数据里的孤儿 id）时整行不显示，
            // 留一个空的「分类」标签反而像出了 bug。
            val categoryName = detail.categoryId?.let { id -> categories.firstOrNull { it.id == id }?.name }
            if (categoryName != null) {
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 信息卡片：每行一个字段，行尾放该字段的动作
        CardSurface {
            DetailField(label = "用户名", value = detail.username, onCopy = onCopyUsername)
            DetailField(
                label = "密码",
                value = revealed ?: MASKED_PASSWORD,
                masked = revealed == null,
                onToggleVisibility = { if (revealed == null) onReveal() else onHide() },
                onCopy = onCopy,
            )
            val url = detail.url
            if (!url.isNullOrEmpty()) {
                DetailField(
                    label = "网址",
                    value = url,
                    // 无 scheme 的裸域名系统打不开，补上 https 协议头。
                    onTrailing = {
                        IconButton(onClick = { uriHandler.openUri(if ("://" in url) url else "https://$url") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_open_in_new),
                                contentDescription = "用浏览器打开网址",
                            )
                        }
                    },
                )
            }
            // 二期占位：TOTP 属 v0.10 已延期的需求，先把位置占住（D5）
            DetailField(label = "动态验证码 TOTP", value = "即将支持", enabled = false)
            val notes = detail.notes
            if (!notes.isNullOrEmpty()) {
                DetailField(label = "备注", value = notes)
            }
            // D7 保留标签：编辑页能录入、搜索能按 tag 筛选，但只有录入没有展示的话
            // 用户再也看不到自己打的标签，故详情页留一行（D7 明确要求保留标签）。
            if (detail.tags.isNotEmpty()) {
                DetailField(label = "标签", value = detail.tags.joinToString("、"))
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        // 底部动作区：编辑 + 收藏（分享因涉及明文外传，本轮不做，见 spec D5）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("编辑")
            }
            OutlinedButton(
                onClick = { onToggleFavorite(!detail.favorite) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (detail.favorite) Icons.Default.Star else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (detail.favorite) "已收藏" else "收藏")
            }
        }
        Spacer(Modifier.height(16.dp))
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

/** 掩码后的密码占位串。与 [DetailField.masked] 同源，避免用字面量比对判断是否已显隐。 */
private const val MASKED_PASSWORD = "••••••••"

/**
 * 详情页的信息行：标签 + 值 + 可选动作按钮。
 *
 * 自绘 drawable 一律经 [Icon] + `painterResource` 消费而不能用 `Image`：这些 vector
 * 内部带 `android:tint="?attr/colorControlNormal"`，走 `Image` 时该 attr 直接生效、
 * 暗色模式不跟随主题；`Icon` 的 `ColorFilter.tint`（SrcIn）会把它覆写成主题色。
 */
@Composable
private fun DetailField(
    label: String,
    value: String,
    enabled: Boolean = true,
    masked: Boolean = false,
    onCopy: (() -> Unit)? = null,
    onToggleVisibility: (() -> Unit)? = null,
    onTrailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                // 备注可到 2000 字、网址到 2048 字。不限行数的话单条超长值会
                // 把整张卡片撑到屏幕外；截断 + 省略号保证页面结构不被单个字段破坏。
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onToggleVisibility?.let { toggle ->
            IconButton(onClick = toggle) {
                Icon(
                    painter = painterResource(if (masked) R.drawable.ic_eye else R.drawable.ic_eye_off),
                    contentDescription = if (masked) "显示密码" else "隐藏密码",
                )
            }
        }
        onTrailing?.let { it() }
        onCopy?.let { copy ->
            IconButton(onClick = copy) {
                Icon(painter = painterResource(R.drawable.ic_copy), contentDescription = "复制")
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
