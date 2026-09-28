package com.zz.passwordmanager.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.domain.entries.EntrySummary

/**
 * 条目行：头像 + 标题 + 收藏星标 + 副标题 + 右箭头。
 *
 * 收藏星标是**纯展示、不可点**：设计稿的列表页里星标只标记状态，切换收藏在
 * 详情页做，整行的 [onClick] 唯一通向详情。故本组件不提供收藏回调——留一个
 * 只能传空 lambda 的形参，会诱使调用方接上真回调，与整行点击抢事件。
 */
@Composable
fun EntryRow(entry: EntrySummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(entry.title)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (entry.favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "已收藏",
                            // 收藏用琥珀色而非 primary：靛蓝星标与主题主色同源，扫不出「选中」感。
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
                if (entry.subtitle.isNotBlank()) {
                    Text(
                        text = entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
