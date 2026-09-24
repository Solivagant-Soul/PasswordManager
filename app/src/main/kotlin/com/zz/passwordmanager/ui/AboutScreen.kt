package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 关于页（T46/FR-10）。
 *
 * 存储声明按 C5 决策**改写**为「本地加密存储 · 不上传云端」——设计稿原文是
 * 「端到端加密 · 零知识架构」，但本应用纯本地、无服务端、无传输链路，
 * 那个说法指涉不存在的第二个端点，属虚假信任文案。
 */
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("关于", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("密码管理器", style = MaterialTheme.typography.titleLarge)
        Text("版本 v$versionName", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Text("数据存储", style = MaterialTheme.typography.titleMedium)
        Text(
            "全部数据仅保存在本机，不上传云端，无账号体系。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "密码库经 Argon2id 派生主密钥、AES-256-GCM 加密，数据库另由 SQLCipher 整库加密。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        Text("安全提示", style = MaterialTheme.typography.titleMedium)
        Text(
            "忘记主密码且忘记密保问题答案时，数据不可恢复——本应用没有云备份。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "剪贴板中的密码会在 30 秒后自动清理；应用切到后台超过设定时长后自动锁定。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
