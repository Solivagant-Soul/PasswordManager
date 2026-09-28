package com.zz.passwordmanager.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.password.GeneratorOptions
import com.zz.passwordmanager.domain.password.PasswordGenerator
import com.zz.passwordmanager.domain.password.PasswordStrength
import com.zz.passwordmanager.ui.components.CardSurface
import com.zz.passwordmanager.ui.components.PageHeader
import com.zz.passwordmanager.ui.theme.SuccessGreenAccent

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
            StrengthBar(level = level)
            Text(
                text = "强度：${level.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 进度条铺满整行，标签居中才与它同轴；沿用默认左对齐会贴到卡片左缘。
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("长度", modifier = Modifier.weight(1f))
                // 数字放进圆角徽章而非裸文本：拖滑块时它是变化的焦点，裸数字在
                // 长文本基线旁不显眼，徽章让当前值一眼可辨。
                Text(
                    text = "$length",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            Slider(
                value = length.toFloat(),
                onValueChange = { length = it.toInt() },
                valueRange = PasswordGenerator.LENGTH_MIN.toFloat()..PasswordGenerator.LENGTH_MAX.toFloat(),
                steps = PasswordGenerator.LENGTH_MAX - PasswordGenerator.LENGTH_MIN - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            // 滑块与开关组之间也要一条，否则那一段会与上面的长度行糊在一起
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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

/**
 * 强度条：设计稿画的是「绿 → 靛」渐变填充。
 *
 * 用 `Canvas` + `Brush.linearGradient` 自绘而非 `LinearProgressIndicator`：M3 的
 * 进度条只接受单一 `LinearProgressIndicatorDefaults.color`，要渐变只能进绘制层。
 * 底槽仍用 surfaceVariant（与分隔线同槽），暗色下不刺眼。
 */
@Composable
private fun StrengthBar(level: PasswordStrength.Level) {
    val fraction = level.fraction
    val track = MaterialTheme.colorScheme.surfaceVariant
    // 渐变横跨整条满宽轨道而非只覆盖已填充段：低强度时色相也偏绿，
    // 与设计稿「越满越靛」的观感一致。
    val brush = Brush.linearGradient(
        colors = listOf(SuccessGreenAccent, MaterialTheme.colorScheme.primary),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, 0f),
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        drawRoundRect(color = track, cornerRadius = CornerRadius(size.height / 2))
        if (fraction > 0f) {
            drawRoundRect(
                brush = brush,
                size = Size(size.width * fraction, size.height),
                cornerRadius = CornerRadius(size.height / 2),
            )
        }
    }
}

/**
 * 生成器的字符集开关行。
 *
 * 分隔线画在行自身下方而非由调用方插入，与 `SettingRow` 同一套做法：设计稿里
 * 分隔线贯穿整行、与卡片同宽，放在组件里能保证每行都有一条，不会因调用方漏插而参差。
 */
@Composable
private fun CharSetToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
