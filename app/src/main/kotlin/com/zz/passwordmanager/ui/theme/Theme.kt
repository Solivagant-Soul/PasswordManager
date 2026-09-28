package com.zz.passwordmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 视觉体系（v2.1/§5.1）。取色自 `DesignDocument/产品原型图/`。
 *
 * 此前 `MainActivity` 用的是裸 `MaterialTheme {}`——M3 默认紫，与设计稿的
 * 靛蓝 + 淡薰衣草底完全是两套，故本轮建立显式配色。
 */
private val Indigo = Color(0xFF4F46E5)
private val Lavender = Color(0xFFF4F4FA)
private val DangerRed = Color(0xFFEF4444)
private val SuccessGreen = Color(0xFF22C55E)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E3FB),
    onPrimaryContainer = Color(0xFF1E1B72),
    background = Lavender,
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDEBF7),
    onSurfaceVariant = Color(0xFF6B7280),
    error = DangerRed,
    // M3 1.3 新增槽位。不显式赋值会静默回落到 M3 基线紫（outline=#79747E、
    // surfaceContainer=#F3EDF7），紫色描边落在靛蓝表单上明显偏色。
    outline = Color(0xFF9CA3AF),
    outlineVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDFDFF),
    surfaceContainer = Color(0xFFF7F6FC),
    surfaceContainerHigh = Color(0xFFF0EFF8),
    surfaceContainerHighest = Color(0xFFEAE9F4),
    /** 不设则 M3 用基线紫做色调叠加，污染卡片与导航栏。 */
    surfaceTint = Indigo,
    scrim = Color(0xFF000000),
)

/** 设计稿只画了亮色，但夜间解锁屏纯黑过于刺眼；暗色按 M3 规范推导而非硬调色。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B72),
    primaryContainer = Color(0xFF312E81),
    background = Color(0xFF12111A),
    surface = Color(0xFF1C1B24),
    onSurface = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFF9CA3AF),
    error = Color(0xFFFCA5A5),
    // 同亮色：M3 1.3 槽位若不赋值会回落基线紫，暗色下 outline 偏紫同样明显。
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color(0xFF141319),
    surfaceContainerLow = Color(0xFF1C1B24),
    surfaceContainer = Color(0xFF211F2B),
    surfaceContainerHigh = Color(0xFF2A2833),
    surfaceContainerHighest = Color(0xFF33313D),
    surfaceTint = Color(0xFFA5B4FC),
    scrim = Color(0xFF000000),
)

/** 成功色不在 M3 ColorScheme 里，安全中心统计卡与弱密码提示需要它。 */
val SuccessGreenAccent: Color get() = SuccessGreen
val DangerRedAccent: Color get() = DangerRed

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun PasswordManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
