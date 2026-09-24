package com.zz.passwordmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntrySummary

/**
 * 密码列表（T15/T24/T32/T35/T36/T45/T48）。
 *
 * v0.8 按设计稿重做：
 * - 标题 + 副标题「共 N 个条目」（FR-17）
 * - 搜索框
 * - 分类筛选 chips：全部 / 收藏 / 各分类（FR-5，单选互斥）
 * - 条目项：首字母彩色头像 + 标题 + **按类型渲染的副标题**（FR-15）
 *   + 收藏星标 + 右侧箭头
 * - 右下角 FAB「+」新增；右上角锁图标快速锁定（FR-17）
 * - 列表由 Room Flow 驱动（FR-18），增删改后自动更新
 */
@Composable
fun EntryListScreen(
    state: VaultUiState,
    categories: List<Category>,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFavorites: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("所有密码", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "共 ${state.entries.size} 个条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLock) {
                Text("🔒", style = MaterialTheme.typography.titleLarge)
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("搜索密码、网站、标签…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        // 分类筛选：单选一档。「全部」与「收藏」在分类之外，故排在最前。
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.categoryFilter == null && !state.favoritesOnly,
                    onClick = { onSelectCategory(null) },
                    label = { Text("全部") },
                    colors = chipColors(),
                )
            }
            item {
                FilterChip(
                    selected = state.favoritesOnly,
                    onClick = onSelectFavorites,
                    label = { Text("收藏") },
                    colors = chipColors(),
                )
            }
            items(categories, key = { it.id }) { category ->
                FilterChip(
                    selected = state.categoryFilter == category.id,
                    onClick = { onSelectCategory(category.id) },
                    label = { Text(category.name) },
                    colors = chipColors(),
                )
            }
        }

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            val isSearching = state.query.isNotBlank()
            when {
                state.entries.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when {
                            state.favoritesOnly -> "还没有收藏的条目"
                            state.categoryFilter != null -> "该分类下暂无条目"
                            isSearching -> "未找到匹配「${state.query}」的条目"
                            else -> "还没有条目，点击右下角 ＋ 开始"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            onClick = { onOpen(entry.id) },
                            onToggleFavorite = { onOpen(entry.id) },
                        )
                    }
                }
            }

            ExtendedFloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.align(Alignment.BottomEnd),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
        }

        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("设置") }
    }
}

/** 条目行：首字母头像 + 标题 + 类型副标题 + 收藏星标 + 右箭头。 */
@Composable
private fun EntryRow(
    entry: EntrySummary,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val accent = accentFor(entry.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.title.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                if (entry.favorite) {
                    Spacer(Modifier.width(4.dp))
                    Text("★", color = Color(0xFFFFB300))
                }
            }
            // FR-15：副标题按类型渲染——银行卡/证件自动掩码，防止卡号直接暴露在列表
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** 首字母取色：同首字母得到同色，视觉上便于扫读。 */
private fun accentFor(title: String): Color {
    val palette = listOf(
        Color(0xFF5B6ABF), Color(0xFFE0575B), Color(0xFF3BA55C),
        Color(0xFFE08A2E), Color(0xFF9B59B6), Color(0xFF16A085),
    )
    val key = title.firstOrNull()?.code ?: 0
    return palette[((key % palette.size) + palette.size) % palette.size]
}
