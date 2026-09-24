package com.zz.passwordmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.password.PasswordGenerator
import com.zz.passwordmanager.domain.password.PasswordStrength

/**
 * 密码生成器（FR-4/FR-14/T38，按设计稿重做为独立页面）。
 *
 * 与此前弹窗版的差别：结果**常驻显示**、带**强度条与评级**、可「重新生成」与
 * 「复制密码」。从条目编辑页仍可直接调起（复用同一套规则）。
 */
@Composable
fun GeneratorScreen(
    initialLength: Int,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
) {
    var length by remember { mutableIntStateOf(initialLength.coerceIn(PasswordGenerator.LENGTH_MIN, PasswordGenerator.LENGTH_MAX)) }
    var sets by remember { mutableStateOf(PasswordGenerator.CharSet.entries.toSet()) }
    var excludeAmbiguous by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }

    fun regenerate() {
        if (sets.isEmpty()) {
            error = "请至少选择一种字符集"
            return
        }
        error = null
        PasswordGenerator.generate(
            PasswordGenerator.Rules(length, sets, excludeAmbiguous),
        ).onSuccess { password = it.concatToString() }
    }

    // 首次进入即生成，避免空白页
    if (password.isEmpty()) regenerate()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("密码生成器", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = password,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        val level = remember(password) { PasswordStrength.evaluate(password) }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { level.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "强度：${level.label}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("长度", modifier = Modifier.weight(1f))
            Text(
                text = "$length",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = length.toFloat(),
            onValueChange = { length = it.toInt() },
            valueRange = PasswordGenerator.LENGTH_MIN.toFloat()..PasswordGenerator.LENGTH_MAX.toFloat(),
            steps = PasswordGenerator.LENGTH_MAX - PasswordGenerator.LENGTH_MIN - 1,
            onValueChangeFinished = { regenerate() },
            modifier = Modifier.fillMaxWidth(),
        )

        PasswordGenerator.CharSet.entries.forEach { set ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text(
                    text = when (set) {
                        PasswordGenerator.CharSet.UPPER -> "大写字母 A-Z"
                        PasswordGenerator.CharSet.LOWER -> "小写字母 a-z"
                        PasswordGenerator.CharSet.DIGIT -> "数字 0-9"
                        PasswordGenerator.CharSet.SYMBOL -> "符号 !@#\$%^&*"
                    },
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = set in sets,
                    onCheckedChange = { checked ->
                        sets = if (checked) sets + set else sets - set
                        regenerate()
                    },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text("排除易混淆字符", modifier = Modifier.weight(1f))
            Switch(
                checked = excludeAmbiguous,
                onCheckedChange = { excludeAmbiguous = it; regenerate() },
            )
        }

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { regenerate() }, modifier = Modifier.weight(1f)) {
                Text("重新生成")
            }
            Button(
                onClick = { onCopy(password) },
                enabled = password.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("复制密码") }
        }
    }
}
