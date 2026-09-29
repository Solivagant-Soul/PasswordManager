package com.zz.passwordmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zz.passwordmanager.ui.components.TopBar

/** 备份文件的 MIME。SAF 靠它选过滤器，容器本身的判据是魔数 `PMBK`，与此无关。 */
private const val BACKUP_MIME = "application/octet-stream"

/**
 * 「导入 / 导出」设置子页（FR-7，v1.3.0）。
 *
 * 替换 v1.1.0 起的置灰占位行。两处**危险**的事实写在页面正文里而不是藏在弹窗深处，
 * 因为它们决定的是用户此刻该不该按下去：
 * 1. 备份用**主密码**加密——重置主密码（含密保找回重置）会让此前的备份**永久无法解开**；
 * 2. 导入是**整库替换**，不是合并。
 *
 * ## 为什么单独成文件
 * `SettingsSubPages` 已经 361 行，且本页有独立的 SAF 接线与两段式确认流程，
 * 与那五个「纯本地偏好」子页不是一类东西。
 *
 * ## SAF 的可用性
 * 设备上若一个 DocumentsProvider 都没有，`launch()` 抛 `ActivityNotFoundException`。
 * 这里捕获并**如实上报**（`reportSafUnavailable`），不做静默失败——一个点了没反应的
 * 按钮比一句「没有可用的文件选择器」糟糕得多。
 */
@Composable
fun BackupSettingScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    // 导出：确认（含主密码） → SAF 选位置 → 写文件。
    // 主密码必须活过 SAF 那一次往返（用户在文件选择器里可能磨蹭几十秒），
    // 所以它只能暂存在这里；用完立刻置空。JVM 无法清零 String，这是本项目已承认的
    // 固有限制（与 T05 同一批），但至少**不留**——见三处 `pending*Password = ""`。
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = ""
        if (uri == null) return@rememberLauncherForActivityResult // 用户取消了选位置
        viewModel.exportTo(uri, password)
    }

    // 导入：SAF 选文件 → 输主密码并预检 → 看条数 → 确认替换。
    // 选文件在前是有意的：不必让用户先输一次密码，再发现挑错了文件。
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImportPassword by remember { mutableStateOf("") }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        pendingImportPassword = ""
    }

    // 预检成功即从「输密码」切到「确认替换」。用状态派生而非在 onClick 里推进，
    // 这样预检失败时密码框仍然开着、错误就地显示，用户改口令即可重试。
    val pending = state.pendingImport
    LaunchedEffect(pending) { if (pending != null) pendingImportUri = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(title = "导入 / 导出", onBack = onBack)

        Text("备份文件里有什么", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            CONTENT_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text("两条必须知道的事", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            MASTER_PASSWORD_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            REPLACE_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { pendingExportPassword = ""; showExportDialog = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出备份") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                runCatching { importLauncher.launch(arrayOf("*/*")) }
                    .onFailure { viewModel.reportSafUnavailable("导入备份") }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导入备份") }

        if (state.busy) {
            Spacer(Modifier.height(8.dp))
            Text(
                "处理中（需要用主密码派生密钥，请稍候）…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.successMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }

    // ---- 导出确认（含主密码） ----
    if (showExportDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showExportDialog = false; password = "" },
            title = { Text("导出加密备份") },
            text = {
                Column {
                    Text(
                        "备份文件包含全部条目与分类，整个文件用 AES-GCM 加密，" +
                            "密钥由你的主密码派生。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "换设备或找回数据时，需要同一个主密码才能导入。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; viewModel.dismissError() },
                        label = { Text("当前主密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = {
                        showExportDialog = false
                        pendingExportPassword = password
                        password = ""
                        runCatching { exportLauncher.launch("密码备份.pmbak") }
                            .onFailure { pendingExportPassword = ""; viewModel.reportSafUnavailable("导出备份") }
                    },
                ) { Text("选择保存位置") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false; password = "" }) { Text("取消") }
            },
        )
    }

    // ---- 导入第一步：输主密码并预检 ----
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null; pendingImportPassword = "" },
            title = { Text("导入备份") },
            text = {
                Column {
                    Text(
                        "先验证文件能否解开，并统计其中的条目数量。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pendingImportPassword,
                        onValueChange = { pendingImportPassword = it; viewModel.dismissError() },
                        label = { Text("该备份的主密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.errorMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingImportPassword.isNotEmpty() && !state.busy,
                    onClick = { viewModel.inspectImport(uri, pendingImportPassword) },
                ) { Text("验证") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null; pendingImportPassword = "" }) { Text("取消") }
            },
        )
    }

    // ---- 导入第二步：破坏性确认。条数在这里才第一次露面，也必须在这里露出来。 ----
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport(); pendingImportPassword = "" },
            title = { Text("确认导入？", color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text(
                        "这份备份里有 ${target.entryCount} 条条目、${target.categoryCount} 个分类。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "导入会清空当前密码库里的全部条目，然后写入备份中的内容。" +
                            "这是替换，不是合并——当前数据不会保留，也不会自动替你另存一份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.errorMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmImport(pendingImportPassword)
                        pendingImportPassword = ""
                    },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("替换全部") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport(); pendingImportPassword = "" }) { Text("取消") }
            },
        )
    }
}

private val CONTENT_TEXT = """
    · 全部条目：名称、用户名、密码、网址、备注、分类、标签、收藏、类型、创建与修改时间；
    · 全部分类；
    · 整个文件用 AES-GCM 加密，密钥由主密码派生——没有主密码就打不开。
    备份文件不包含密保问题与答案。
""".trimIndent()

private val MASTER_PASSWORD_TEXT = """
    备份用当前主密码加密（没有单独的备份口令）。
    一旦你修改主密码，或用密保问题找回并重置了主密码，之前导出的备份将永久无法再解开——
    原主密码不可取回，密钥也就再也算不出来。重置后请立刻重新导出一份。
""".trimIndent()

private val REPLACE_TEXT = """
    导入是整库替换：确认后当前密码库会被清空，再写入备份里的内容。
    不会合并、不会去重、也不会先自动备份当前数据。导入前请确认你手上有一份想保留的备份。
""".trimIndent()
