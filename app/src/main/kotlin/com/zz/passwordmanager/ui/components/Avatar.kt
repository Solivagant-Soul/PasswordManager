package com.zz.passwordmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 彩色圆角首字母块（设计稿各列表页统一的头像样式）。同首字母取同色，便于扫读。 */
@Composable
fun Avatar(title: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val accent = accentFor(title)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 头像色不取自主题色：条目图标若一律靛蓝，列表会糊成一片。改为按首字母取
 * 固定调色板，同首字母恒得同色，视觉上便于扫读定位。
 *
 * 索引取 `code` 而非 `Char` 本身，是为了让公式对任何 code 恒为非负。
 */
private val AccentPalette = listOf(
    Color(0xFF5B6ABF), Color(0xFFE0575B), Color(0xFF3BA55C),
    Color(0xFFE08A2E), Color(0xFF9B59B6), Color(0xFF16A085),
)

private fun accentFor(title: String): Color {
    val key = title.firstOrNull()?.code ?: 0
    return AccentPalette[((key % AccentPalette.size) + AccentPalette.size) % AccentPalette.size]
}
