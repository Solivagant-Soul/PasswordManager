package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.R
import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.ui.components.PageHeader
import com.zz.passwordmanager.ui.components.SettingRow

/**
 * 设置主页（v2.1/§4.1）：纯设置项列表，每项点进独立子页。
 *
 * 主页必须可滚动——v1 把所有设置塞进一个 `fillMaxSize` 的 Column，内容超出屏幕后
 * 底部的「锁定应用」直接被挤出可视区且无从滚动（F2）。子页内容长度不定，同样按需滚动。
 */
@Composable
fun SettingsScreen(
    biometricEnabled: Boolean,
    categories: List<Category>,
    autoLockDuration: AutoLockDuration,
    allowScreenshots: Boolean,
    autofillEnabled: Boolean,
    defaultPasswordLength: Int,
    appVersionName: String,
    rootDetectionLabel: String,
    errorMessage: String?,
    successMessage: String?,
    onOpenAutoLock: () -> Unit,
    onOpenScreenshots: () -> Unit,
    onOpenRootDetection: () -> Unit,
    onOpenBiometric: () -> Unit,
    onOpenAutofill: () -> Unit,
    onOpenDefaultLength: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onLock: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        PageHeader(title = "设置")
        Spacer(Modifier.height(12.dp))

        // 第一组：解锁与偏好
        SettingsGroup {
            SettingRow(
                title = "生物识别解锁",
                value = if (biometricEnabled) "已开启" else "已关闭",
                icon = { SettingIcon(R.drawable.ic_fingerprint) },
                onClick = onOpenBiometric,
            )
            // v1.1.3：与生物识别同属「凭据外泄面」，故紧随其后。值只显示状态，
            // 作用域（全 App）太长，放子页里讲。
            SettingRow(
                title = "允许截屏",
                value = if (allowScreenshots) "已允许" else "已禁止",
                // 用自绘的「眼睛」而非 material 扩展图标：本项目只依赖 icons-core，
                // Visibility 属于 material-icons-extended，不为此加一个依赖。
                icon = { SettingIcon(R.drawable.ic_eye) },
                onClick = onOpenScreenshots,
            )
            // v2.0.0：紧跟在「允许截屏」后面，因为 root 环境下 FLAG_SECURE 会被绕过——
            // 两者讲的是同一件事的两个半边。检测是启发式的，值只报状态，
            // 「可能失效到什么程度」放子页里讲。
            SettingRow(
                title = "root 环境检测",
                value = rootDetectionLabel,
                icon = { SettingIcon(R.drawable.ic_shield) },
                onClick = onOpenRootDetection,
            )
            SettingRow(
                title = "自动锁定",
                value = autoLockDuration.displayLabel,
                icon = { VectorSettingIcon(Icons.Default.Lock) },
                onClick = onOpenAutoLock,
            )
            // FR-6：开关在**系统设置**里，应用无权自启自举，故这里只报状态 + 导航。
            // 用 Key 图标（无对应自绘 drawable），与「默认密码长度」同款。
            SettingRow(
                title = "自动填充",
                value = if (autofillEnabled) "已开启" else "未开启",
                icon = { SettingIcon(R.drawable.ic_key) },
                onClick = onOpenAutofill,
            )
            SettingRow(
                title = "默认密码长度",
                value = "$defaultPasswordLength",
                icon = { SettingIcon(R.drawable.ic_key) },
                onClick = onOpenDefaultLength,
            )
        }

        // 第二组：内容组织
        SettingsGroup {
            SettingRow(
                title = "分类管理",
                value = "${categories.size} 个",
                icon = { SettingIcon(R.drawable.ic_folder) },
                onClick = onOpenCategories,
            )
            SettingRow(
                title = "修改主密码",
                icon = { SettingIcon(R.drawable.ic_lock_reset) },
                onClick = onOpenChangePassword,
            )
            SettingRow(
                title = "导入 / 导出",
                icon = { SettingIcon(R.drawable.ic_import) },
                onClick = onOpenBackup,
            )
        }

        // 第三组：关于
        SettingsGroup {
            SettingRow(
                title = "关于",
                value = "v$appVersionName",
                icon = { VectorSettingIcon(Icons.Default.Info) },
                onClick = onOpenAbout,
            )
        }

        TextButton(
            onClick = onLock,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("锁定应用", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        successMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 设置项分组卡片：白底圆角卡片壳，行内分隔线由 [SettingRow] 自带。 */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

/**
 * 设置行图标（自绘 drawable）。必须经 [Icon] + `painterResource` 消费而不能用 `Image`：
 * 这些 vector 内部带 `android:tint="?attr/colorControlNormal"`，走 `Image` 时该 attr
 * 直接生效、暗色模式不跟随主题；[Icon] 的 `ColorFilter.tint`（SrcIn）会把它覆写成主题色。
 */
@Composable
private fun SettingIcon(iconRes: Int) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** 设置行图标（内置矢量图标）。与 [SettingIcon] 同理，只是来源为 [ImageVector] 而非 drawable。 */
@Composable
private fun VectorSettingIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}
