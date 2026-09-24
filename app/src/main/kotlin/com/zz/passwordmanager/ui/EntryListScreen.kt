package com.zz.passwordmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.entries.EntrySummary

/** 条目列表 + 搜索（FR-3/FR-5）。点击条目进详情；FAB 语义用底部按钮替代（M2 最小实现）。 */
@Composable
fun EntryListScreen(
    state: VaultUiState,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("搜索标题 / 用户名 / 网址") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.entries, key = { it.id }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry.id) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
                    if (entry.username.isNotEmpty()) {
                        Text(text = entry.username, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                Text("新增条目")
            }
        }
    }
}
