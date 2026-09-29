package com.zz.passwordmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zz.passwordmanager.platform.integrity.RootReport
import com.zz.passwordmanager.platform.integrity.RootVerdict
import com.zz.passwordmanager.ui.components.CardSurface
import com.zz.passwordmanager.ui.components.SectionHeader
import com.zz.passwordmanager.ui.components.TopBar

/**
 * root 环境检测子页（v2.0.0 / FR-10 / NFR-S6）。
 *
 * 本页**只做两件事**：把检测结果如实摆出来，并解释「查的是什么、结论意味着什么」。
 * 刻意没有的东西：任何形式的「去修复」「加固」按钮——本检测没有改变任何防护强度，
 * 给一个点不动的按钮只会暗示用户「点了就安全了」。
 *
 * 检测逻辑与文案全部来自 `:platform` 的 [com.zz.passwordmanager.platform.integrity.RootDetection]，
 * 本文件只负责排版；这样文案本身能被 JVM 单测逐字断言。
 */
@Composable
fun RootDetectionScreen(
    report: RootReport?,
    scanning: Boolean,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        TopBar(title = "root 环境检测", onBack = onBack)

        if (report == null) {
            // 首帧：设置主页那一次探测的结果还没回来。空转圈而不是给结论，
            // 别让「还没查」看起来像「查过了」
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text("正在检查本机环境…")
            }
        } else {
            VerdictCard(report, scanning)
            Spacer(Modifier.height(8.dp))
            Text(
                report.conclusion,
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionHeader("逐项结果")
            report.checks.forEach { check ->
                CardSurface {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(check.signal.label, style = MaterialTheme.typography.bodyMedium)
                            // 命中时把证据（具体哪个路径 / 哪个包）摊开：只说「命中」而不说
                            // 「命中了什么」，等于要用户盲信一个黑盒
                            check.found.forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            when (check.hit) {
                                true -> "命中"
                                false -> "未发现"
                                null -> "未能读取"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (check.hit) {
                                true -> MaterialTheme.colorScheme.error
                                false -> MaterialTheme.colorScheme.onSurfaceVariant
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (report.unknown.isNotEmpty()) {
                Text(
                    "标为「未能读取」的 ${report.unknown.size} 项没有参与判断——" +
                        "系统属性是 Android 的非公开接口，较新的系统可能拒绝读取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader("这个结论意味着什么")
        Explanation()

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRescan,
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (scanning) "正在检查…" else "重新检测") }
    }
}

/** 结论卡：短标签 + 一行计数。措辞全部来自报告，本组件不追加任何判断词。 */
@Composable
private fun VerdictCard(report: RootReport, scanning: Boolean) {
    CardSurface {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                report.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (report.verdict == RootVerdict.SUSPECTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                "已检查 ${report.checkedCount} 项，命中 ${report.hits.size} 项" +
                    if (scanning) " · 正在重新检测…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「查的是什么、意味着什么」的固定说明。
 *
 * 三件事必须一直写在这里：
 * 1. **它是启发式，不是保证**——包括没查出来的时候；
 * 2. **没有 Play Integrity 可用**：本 App 是自签自用的侧载 APK，不经 Google Play，
 *    故拿不到 Google 的设备完整性结论，只能本机自查（这是架构事实，不是待补项）；
 * 3. **「越狱」是 iOS 的概念**，Android 没有对应物；Android 上与之最接近的
 *    「设备已被攻破」形态是 root、解锁 bootloader、OEM 解锁，本页查的只是第一类的迹象。
 */
@Composable
private fun Explanation() {
    Text(
        "这一页检查的都是本机可直接读到的事实：几个常见路径下有没有 su、busybox、" +
            "Magisk 的文件，系统里有没有已知的 root 管理器应用，几个系统分区能不能写，" +
            "以及系统是不是以测试密钥签名、以可调试方式构建。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "结论是启发式的：每一条本地检查都有办法被绕过，一次没查出来也不代表设备是安全的。" +
            "本 App 是自签自用的侧载安装包，不经 Google Play，因此没有、也不会接入 " +
            "Play Integrity 一类的 Google 设备完整性服务——能做的只有上面这些本机自查。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "顺带说明：「越狱」是 iOS 的说法，Android 没有对应概念。" +
            "Android 上与「越狱」最接近的是设备已被 root、bootloader 已解锁或做过 OEM 解锁；" +
            "本页只查得出第一类的迹象，后两者需要系统设置或厂商界面的信息。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "无论结论如何，本应用都不会限制任何功能：密码库照常加密、条目照常增删改查。" +
            "本页存在的意义是让你知道风险，而不是替你做决定。",
        style = MaterialTheme.typography.bodyMedium,
    )
}
