package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.R
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.ui.components.TopBar

/**
 * 新增/编辑表单（FR-3/T16）：必填校验（标题/密码）在客户端先行提示，
 * 服务级校验错误经 errorMessage 展示。
 *
 * v2.1/D1：条目类型没有 UI 入口了（与「分类」语义重复），列仍在库中；D4：「⚡生成」
 * 零弹窗直填。
 */
@Composable
fun EntryEditScreen(
    existing: com.zz.passwordmanager.domain.entries.EntryDetail?,
    categories: List<com.zz.passwordmanager.domain.entries.Category>,
    revealed: String?,
    errorMessage: String?,
    onGeneratePassword: () -> String?,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onSave: (EntryInput) -> Unit,
    onBack: () -> Unit,
) {
    // T17：离开编辑页即清除内存中的明文。切 Tab 也会走这里——Change 2 让点 Tab 回到根页，
    // 换 Tab 即离开本页，onDispose 照常触发。
    DisposableEffect(Unit) {
        onDispose { onHide() }
    }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    // 编辑时不回填明文（明文不残留，T17）。用户主动点眼睛才把 revealed 填进来，
    // 一旦用户自己改过这个字段（`passwordEdited`），就断开与 revealed 的联动——
    // 否则下一轮明文回写会把用户刚输入的内容覆盖掉。
    var password by remember { mutableStateOf("") }
    var passwordEdited by remember { mutableStateOf(false) }
    val shownPassword = if (passwordEdited) password else (revealed ?: password)
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }
    // FR-5 分类：单选筛选维度
    var categoryId by remember { mutableStateOf(existing?.categoryId) }

    // 收藏的 UI 入口已收敛到详情页，但 update() 会写 favorite 列
    // （见 data/…/EntryRepositoryImpl 的 `favorite = input.favorite`）——
    // 这里若硬编码 false，用户每次编辑保存都会清空已收藏的条目。
    // 故用 remember 持有原值、不渲染 UI、提交时原样带回。
    val favorite = remember(existing?.id) { existing?.favorite ?: false }
    // 同理：类型 chips 已按 D1 删除，而 update() 也写 type 列，故原样带回旧值，
    // 免得编辑一次就把 FR-15 的类型打回 ACCOUNT 默认档。
    val type = remember(existing?.id) { existing?.type ?: com.zz.passwordmanager.domain.entries.EntryType.ACCOUNT }

    // 顶栏「保存」与底部按钮共用，避免两处构造逻辑漂移
    fun currentInput(): EntryInput = EntryInput(
        title = title.trim(),
        username = username.trim(),
        password = shownPassword.toCharArray(),
        url = url.trim().ifEmpty { null },
        notes = notes.ifEmpty { null },
        categoryId = categoryId,
        favorite = favorite,
        type = type,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(
            title = if (existing == null) "新建条目" else "编辑条目",
            onBack = onBack,
            action = {
                TextButton(
                    enabled = title.isNotBlank() && (existing == null || shownPassword.isNotEmpty()),
                    onClick = { onSave(currentInput()) },
                ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
        )

        FieldCard {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                placeholder = { Text("例如 GitHub") },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                placeholder = { Text("user@example.com") },
                label = { Text("用户名 / 账号") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = shownPassword,
                onValueChange = { password = it; passwordEdited = true },
                placeholder = { Text("输入或生成密码") },
                label = { Text("密码") },
                trailingIcon = {
                    Row {
                        // 「显示原密码」：T17 不允许回填明文，但用户改密码时常常需要
                        // 确认自己此刻填的是不是原密码，故给一个显式开关——明文只经
                        // reveal 进 revealed，绝不主动写进表单初值。新建条目没有既有
                        // 密码可显示，图标不出现。
                        if (existing != null) {
                            IconButton(onClick = { if (revealed == null) onReveal() else onHide() }) {
                                Icon(
                                    painter = painterResource(
                                        // 与详情页 DetailField 同一套图标语义：当前是掩码态
                                        // 就画「睁眼」（点了显示），已明文则画「闭眼」
                                        if (revealed == null) R.drawable.ic_eye else R.drawable.ic_eye_off,
                                    ),
                                    contentDescription = if (revealed == null) "显示原密码" else "隐藏密码",
                                )
                            }
                        }
                        // 零弹窗直填：用生成器页同一套全局选项（D4）
                        IconButton(onClick = {
                            // 不用 `?.let{} ?: 提示` 的写法：赋值表达式返回 Unit（非空），
                            // 那个 Elvis 右侧永远不会触发，空字符集时就会静默无反应。
                            val generated = onGeneratePassword()
                            if (generated != null) { password = generated; passwordEdited = true }
                            else localError = "请先在「生成」页至少勾选一类字符集"
                        }) {
                            // 自绘 drawable 走 Icon + painterResource：Image 会让 drawable 里
                            // 的 ?attr/colorControlNormal 直接生效，暗色模式不跟随主题色。
                            Icon(painter = painterResource(R.drawable.ic_bolt), contentDescription = "生成密码")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                placeholder = { Text("https://") },
                label = { Text("网址") },
                modifier = Modifier.fillMaxWidth(),
            )

            // 分类（FR-5）：单选，可清空回到「未分类」。类型（FR-15）已按 D1 移除
            Text("分类", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { categoryId = null },
                        label = { Text("未分类") },
                    )
                }
                items(categories, key = { it.id }) { category ->
                    FilterChip(
                        selected = categoryId == category.id,
                        onClick = { categoryId = category.id },
                        label = { Text(category.name) },
                    )
                }
            }

            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                placeholder = { Text("选填") },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        (localError ?: errorMessage)?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val issues = mutableListOf<String>()
                if (title.trim().isEmpty()) issues += "标题必填"
                if (shownPassword.isEmpty()) issues += "密码必填"
                if (issues.isNotEmpty()) {
                    localError = issues.joinToString("；")
                    return@Button
                }
                localError = null
                onSave(currentInput())
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
    }
}

/** 编辑页字段区：白底圆角卡片，字段之间留白。 */
@Composable
private fun FieldCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
