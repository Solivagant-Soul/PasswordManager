package com.zz.passwordmanager.autofill

import android.app.PendingIntent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.zz.passwordmanager.data.vault.VaultStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 系统级自动填充服务（FR-6，v1.2.0）。
 *
 * ## 本类**故意不持有任何密钥材料**
 *
 * 它不注入 [com.zz.passwordmanager.data.vault.AutofillVaultSession]、不注入
 * [com.zz.passwordmanager.data.vault.UnlockVault]、没有任何密钥字段。唯一注入的
 * [VaultStatus] 只回答「库在不在」（读 Header 有无，不含 DEK）。所以
 * 「先解锁一次 App、之后不锁也能从任意 App 拉凭据」这条路在结构上就不存在——
 * 不是靠运行时判断拦住的，是压根没有可用的句柄。
 *
 * ## 会话在哪儿
 *
 * 走 Android 推荐的 `Dataset.setAuthentication` 模式：`onFillRequest` 只回一个
 * 「解锁密码库以填充」的占位 Dataset，它的值是**空的**、只有一个认证 IntentSender。
 * 系统只在用户真的点了那一条时才拉起 [AutofillAuthActivity]；DEK 只存在于那个
 * Activity 的生命周期内，`finally` 与 `onDestroy` 双路清零（见该类 KDoc）。
 *
 * ## 降级
 *
 * - 库还没初始化 / 表单里识别不出密码框 → `onSuccess(null)`，什么都不给（S40）；
 * - 识别得出但没匹配项 → 认证页里显示「无匹配条目」，同样不填（S39）；
 * - 服务未在系统设置里启用 → 系统根本不会回调本类，应用其余功能不受影响。
 *
 * `onSaveRequest` 显式回失败：把新凭据存进库属另一条链路（需解密 + 落库 + 校验），
 * 与「回填已有条目」是两件事，本版明确不做，避免半条链路拿着 DEK 悬着。
 */
@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject
    lateinit var vaultStatus: VaultStatus

    /**
     * 入口签名是 **三参**版（API 31+）。
     *
     * 两参版 `onFillRequest(FillRequest, FillCallback)` 虽自 API 26 就在，
     * 但 API 31 起被弃用、并已从 compileSdk 35 的桩里移除——想同时兼容 26–30
     * 就得对着一个已经没有该方法的桩来 override，编不过。
     * 因此本服务实际生效于 **API 31 及以上**；26–30 上系统调的是我们没有覆盖的
     * 两参默认实现（空实现），表现为「开了服务但不出建议」，不崩、不泄露。
     * 设置页会照实提示这一条。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)

        val form = runCatching { AutofillStructureReader.read(structure) }.getOrNull()
        if (form == null) return callback.onSuccess(null)
        if (!runCatching { vaultStatus.isInitialized() }.getOrDefault(false)) {
            return callback.onSuccess(null)
        }

        val authIntent = AutofillAuthActivity.newIntent(this, form)
        // FLAG_MUTABLE 是硬要求：系统要往这个 IntentSender 里塞本次填充的上下文，
        // 不可变的 PendingIntent 会让认证分支拿不到数据。
        val sender = PendingIntent.getActivity(
            this,
            0,
            authIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
            setTextViewText(android.R.id.text1, "解锁密码库以填充")
            setTextViewText(android.R.id.text2, "目标：${describe(form)}")
        }

        val dataset = Dataset.Builder()
            // value 传 null：这一条只提供「点我」，不预置任何内容。
            // 用户不点，系统就不会填；点了才进认证分支。
            .setValue(form.passwordId, null, presentation)
            .setAuthentication(sender.intentSender)
            .build()

        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }

    /**
     * 不支持「把新输的凭据存进库」。空实现 = 明确不做。
     *
     * 这不是偷懒：保存链路要拿明文做校验再落库，是与「回填已有条目」完全不同的
     * 一条路径，半条链路拿着 DEK 悬着才是风险（见类 KDoc）。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onSaveRequest(
        request: android.service.autofill.SaveRequest,
        callback: android.service.autofill.SaveCallback,
    ) {
        callback.onFailure("密码管理暂不支持保存新凭据")
    }

    private fun describe(form: ParsedForm): String =
        form.target.domain ?: form.target.packageName ?: "未知来源"
}
