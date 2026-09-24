package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.security.SecurityReport

/**
 * 安全中心（FR-13/T43）。
 *
 * 四张统计卡对应四个**本地可算**指标：总密码数 / 弱密码 / 重复使用 / 长期未更新。
 * 没有「已泄露」——那需要联网查询泄露库，与「纯本地、无网络请求」冲突（C2 决策）。
 */
@Composable
fun SecurityCenterScreen(
    state: VaultUiState,
    onScan: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val report = state.securityReport

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("安全中心", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = report?.let { "上次扫描：${formatTime(it.scannedAtEpochMs)}" } ?: "尚未扫描",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (report == null) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (state.scanning) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在本地扫描…")
                    }
                } else {
                    Button(onClick = onScan) { Text("开始安全检测") }
                }
            }
            return@Column
        }

        // 统计卡
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("总密码数", report.totalCount, Modifier.weight(1f))
            StatCard("弱密码", report.weakCount, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("重复使用", report.duplicateGroups.size, Modifier.weight(1f))
            StatCard("长期未更新", report.staleCount, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Text("需处理（${report.actionableCount}）", style = MaterialTheme.typography.titleMedium)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(report.weakEntryIds, key = { "weak-$it" }) { id ->
                IssueRow("⚠", "该密码强度弱，建议 16 位以上", id, onOpenEntry)
            }
            items(report.duplicateGroups, key = { it.joinToString("-") }) { group ->
                IssueRow(
                    icon = "🔁",
                    text = "与另外 ${group.size - 1} 个条目重复使用",
                    entryId = group.first(),
                    onOpenEntry = onOpenEntry,
                )
            }
            items(report.staleEntryIds, key = { "stale-$it" }) { id ->
                IssueRow("🕰", "超过 180 天未更新", id, onOpenEntry)
            }
            if (report.actionableCount == 0) {
                item { Text("没有需要处理的条目", modifier = Modifier.padding(16.dp)) }
            }
        }

        TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("重新检测") }
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IssueRow(icon: String, text: String, entryId: String, onOpenEntry: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { onOpenEntry(entryId) }) { Text("去修改") }
    }
}

private fun formatTime(epochMs: Long): String {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return d.format(java.util.Date(epochMs))
}
