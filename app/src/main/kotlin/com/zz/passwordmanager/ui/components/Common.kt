package com.zz.passwordmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 公共组件包（v2.1/§5.1）。
 *
 * 设计稿里所有信息块——页头、顶栏、卡片、小节标题、统计卡、列表行、设置行——
 * 是同一套视觉语言。散着写在各 Screen 里会各自漂移（同一个 16.dp 圆角这里写
 * 14、那里写 18），故统一抽到这里，六屏统一 import。
 */

/** 左上角大标题 + 可选副标题 + 可选右侧动作（设计稿「所有密码 / 共 4 个条目」形态）。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 返回键 + 居中标题 + 可选右侧动作（设计稿「新建条目 / 保存」形态）。
 *
 * 三者用 [Box] 叠放而非 [Row] 并排：并排时 `weight(1f)` 只能让标题占满剩余
 * 空间，文本自身仍左对齐，且右侧有无动作时居中位置会漂移。叠放 + `align`
 * 才能让标题真正居中，右侧动作宽度变化也不影响它。
 */
@Composable
fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center),
        )
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            // 用 AutoMirrored 版：RTL 布局下普通 ArrowBack 不会跟着镜像。
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        action?.let { Box(modifier = Modifier.align(Alignment.CenterEnd)) { it() } }
    }
}

/** 白底圆角卡片容器：设计稿里所有信息块的统一外壳。 */
@Composable
fun CardSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // 设计稿的卡片靠底色差分层、不靠投影，故默认零高度。
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // 水平内边距放在壳里而非各调用方：直接用 CardSurface 的页面（生成器）
        // 否则每个元素都会贴到卡片左右边缘。已在内容里自带水平内边距的调用方
        // 需自行去掉那一份，避免叠加。
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

/** 小节标题（「需要处理」「安全建议」）。 */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** 安全中心的统计卡：大数字 + 标签 + 左侧彩条。 */
@Composable
fun StatCard(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .background(accent, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
