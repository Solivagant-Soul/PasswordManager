package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.password.PasswordGenerator

/**
 * 密码生成器规则弹窗（FR-4/T19）。
 *
 * 校验错误（feature 04「长度超出范围」「未选择字符集」）在此就地展示并**纠正输入**，
 * 不生成密码；生成成功则回填调用方的密码框。
 */
@Composable
fun GeneratorDialog(
    onDismiss: () -> Unit,
    onGenerate: (PasswordGenerator.Rules) -> Unit,
) {
    var lengthText by remember { mutableStateOf("16") }
    var excludeAmbiguous by remember { mutableStateOf(true) }
    var sets by remember {
        mutableStateOf(PasswordGenerator.CharSet.entries.toSet())
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = lengthText,
                    onValueChange = { lengthText = it.filter(Char::isDigit).take(3) },
                    label = { Text("长度（${PasswordGenerator.LENGTH_MIN}–${PasswordGenerator.LENGTH_MAX}）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordGenerator.CharSet.entries.forEach { set ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = set in sets,
                            onCheckedChange = { checked ->
                                sets = if (checked) sets + set else sets - set
                            },
                        )
                        Text(setLabel(set))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = excludeAmbiguous, onCheckedChange = { excludeAmbiguous = it })
                    Text("排除易混淆字符（0 O 1 l I |）")
                }
                error?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val length = lengthText.toIntOrNull()
                    if (length == null || length !in PasswordGenerator.LENGTH_MIN..PasswordGenerator.LENGTH_MAX) {
                        error = "长度需在 ${PasswordGenerator.LENGTH_MIN} 到 ${PasswordGenerator.LENGTH_MAX} 之间"
                        return@TextButton
                    }
                    if (sets.isEmpty()) {
                        error = "请至少选择一种字符集"
                        return@TextButton
                    }
                    onGenerate(
                        PasswordGenerator.Rules(
                            length = length,
                            sets = sets,
                            excludeAmbiguous = excludeAmbiguous,
                        ),
                    )
                },
            ) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun setLabel(set: PasswordGenerator.CharSet) = when (set) {
    PasswordGenerator.CharSet.UPPER -> "大写字母"
    PasswordGenerator.CharSet.LOWER -> "小写字母"
    PasswordGenerator.CharSet.DIGIT -> "数字"
    PasswordGenerator.CharSet.SYMBOL -> "符号"
}
