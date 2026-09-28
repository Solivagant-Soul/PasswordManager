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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.ui.components.PageHeader
import com.zz.passwordmanager.ui.components.SectionHeader
import com.zz.passwordmanager.ui.components.StatCard

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

    // v2.1/F6：进入本页即自动扫描，无需手点；已有结果时不重复扫。
    // 守卫 `!state.locked`：扫描器走 `entryRepository.search(...)`，锁定态取不到数据，
    // 会扫出一份全 0 的假报告。底栏在锁定时本就隐藏，但这里仍自查一层，
    // 避免将来任何路径把本页暴露给锁定态。
    LaunchedEffect(state.securityReport) {
        if (state.securityReport == null && !state.scanning && !state.locked) onScan()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PageHeader(
            title = "安全中心",
            subtitle = report?.let { "上次扫描：${formatTime(it.scannedAtEpochMs)}" } ?: "正在扫描…",
        )
        Spacer(Modifier.height(16.dp))

        if (report == null) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.scanning) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在本地扫描…")
                    } else {
                        // 扫描失败时 `securityReport` 仍为 null，而上面的 LaunchedEffect
                        // 以它为 key、值没变 → 不会重跑。必须留手动重试出口，
                        // 否则用户永久卡在空白页。
                        Text(
                            "扫描未完成，请重试",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onScan) { Text("重试") }
                    }
                }
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("${report.totalCount}", "总密码数", Color(0xFF5B6ABF), Modifier.weight(1f))
            StatCard("${report.weakCount}", "弱密码", Color(0xFFE08A2E), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("${report.duplicateGroups.size}", "重复使用", Color(0xFF3BA55C), Modifier.weight(1f))
            StatCard("${report.staleCount}", "长期未更新", Color(0xFFE0575B), Modifier.weight(1f))
        }

        SectionHeader("需要处理")

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
