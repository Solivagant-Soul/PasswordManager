package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntrySummary
import com.zz.passwordmanager.ui.components.EntryRow
import com.zz.passwordmanager.ui.components.PageHeader

/**
 * 密码列表（T15/T24/T32/T35/T36/T45/T48 + v2.1/F7）。
 *
 * v2.1 变化：删除页底「设置」按钮——它与底栏的「设置」Tab 重复（F7），
 * 设置入口统一走底栏。
 *
 * 版面按设计稿自上而下：页头（所有密码 / 共 N 个条目 + 锁图标）→ 搜索框 →
 * 分类 chips → 条目列表，右下角 FAB 新增。
 */
@Composable
fun EntryListScreen(
    state: VaultUiState,
    categories: List<Category>,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onLock: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFavorites: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PageHeader(
            title = "所有密码",
            subtitle = "共 ${state.entries.size} 个条目",
            trailing = {
                IconButton(onClick = onLock) {
                    Icon(Icons.Default.Lock, contentDescription = "锁定应用")
                }
            },
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索密码、网站、标签…") },
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
                )
            }
            item {
                FilterChip(
                    selected = state.favoritesOnly,
                    onClick = onSelectFavorites,
                    label = { Text("收藏") },
                )
            }
            items(categories, key = { it.id }) { category ->
                FilterChip(
                    selected = state.categoryFilter == category.id,
                    onClick = { onSelectCategory(category.id) },
                    label = { Text(category.name) },
                )
            }
        }

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.entries.isEmpty()) {
                EmptyHint(state)
            } else {
                // 96 > FAB 56 + 上下各 16 留白：末行滚到底时正好露出 FAB 上沿，不被压住
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            onClick = { onOpen(entry.id) },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = onCreate,
                // 16.dp 边距：贴边时 FAB 压住内容框边缘，视觉上不像「浮」在列表之上
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "新增条目",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * 空态提示。文案随当前筛选维度变化，让用户知道「空」是筛选造成的还是真的没数据。
 */
@Composable
private fun EmptyHint(state: VaultUiState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                state.favoritesOnly -> "还没有收藏的条目"
                state.categoryFilter != null -> "该分类下暂无条目"
                state.query.isNotBlank() -> "未找到匹配「${state.query}」的条目"
                else -> "还没有条目，点击右下角 ＋ 开始"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
