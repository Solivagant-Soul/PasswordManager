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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.password.GeneratorOptions
import com.zz.passwordmanager.domain.password.PasswordGenerator
import com.zz.passwordmanager.domain.password.PasswordStrength
import com.zz.passwordmanager.ui.components.CardSurface
import com.zz.passwordmanager.ui.components.PageHeader

/**
 * 密码生成器（FR-4/FR-14/T38 + v2.1/F5/D4）。
 *
 * v2.1 变化：
 * - **去掉顶部返回键**——它是 Tab 根页，没有回退语义（F5）。
 * - 选项读写**全局偏好**（DataStore），与编辑页「⚡生成」共用同一份（D4）。
 * - 长度上界 32（Task 2）。
 */
@Composable
fun GeneratorScreen(
    initialLength: Int,
    options: GeneratorOptions,
    onOptionsChange: (GeneratorOptions) -> Unit,
    onCopy: (String) -> Unit,
) {
    var length by remember {
        mutableIntStateOf(initialLength.coerceIn(PasswordGenerator.LENGTH_MIN, PasswordGenerator.LENGTH_MAX))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }

    fun regenerate() {
        if (options.charSets().isEmpty()) {
            error = "请至少选择一种字符集"
            password = ""
            return
        }
        error = null
        PasswordGenerator.generate(options.rules(length)).onSuccess { password = it.concatToString() }
    }

    // 首次进入即生成，避免空白页；选项或长度变化时同步重算
    LaunchedEffect(length, options) { regenerate() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        PageHeader(title = "密码生成器")
        Spacer(Modifier.height(12.dp))

        // 设计稿把「结果 + 强度 + 长度 + 字符集开关」画成**一整块**浮在淡紫底上的
        // 白卡（不是四张散卡）。故整块包进一个 CardSurface，而不是只给结果框上色。
        CardSurface {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = password.ifEmpty { "—" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }

            val level = remember(password) { PasswordStrength.evaluate(password) }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { level.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                text = "强度：${level.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("长度", modifier = Modifier.weight(1f))
                Text(
                    "$length",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = length.toFloat(),
                onValueChange = { length = it.toInt() },
                valueRange = PasswordGenerator.LENGTH_MIN.toFloat()..PasswordGenerator.LENGTH_MAX.toFloat(),
                steps = PasswordGenerator.LENGTH_MAX - PasswordGenerator.LENGTH_MIN - 1,
                modifier = Modifier.fillMaxWidth(),
            )

            CharSetToggle("大写字母 A-Z", checked = options.useUpper) {
                onOptionsChange(options.copy(useUpper = it))
            }
            CharSetToggle("小写字母 a-z", checked = options.useLower) {
                onOptionsChange(options.copy(useLower = it))
            }
            CharSetToggle("数字 0-9", checked = options.useDigit) {
                onOptionsChange(options.copy(useDigit = it))
            }
            CharSetToggle("符号 !@#\$%^&*", checked = options.useSymbol) {
                onOptionsChange(options.copy(useSymbol = it))
            }
            CharSetToggle("排除易混淆字符", checked = options.excludeAmbiguous) {
                onOptionsChange(options.copy(excludeAmbiguous = it))
            }

            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { regenerate() }, modifier = Modifier.weight(1f)) { Text("重新生成") }
            Button(onClick = { onCopy(password) }, enabled = password.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("复制密码")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 生成器的字符集开关行。 */
@Composable
private fun CharSetToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
