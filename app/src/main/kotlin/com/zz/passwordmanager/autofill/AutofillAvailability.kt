package com.zz.passwordmanager.autofill

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager

/**
 * 自动填充服务的启用状态与跳转（FR-6 的开关入口）。
 *
 * 应用**无法自行启用**填充服务——这是系统的安全边界：谁在往表单里塞凭据，必须由
 * 用户在系统设置里亲手点头。所以这里只做两件事：如实报告当前状态、把用户送到系统
 * 那一页（`Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`，API 26+）。
 */
object AutofillAvailability {

    /** 本 App 的填充服务是否已被用户启用。取不到就按未启用处理（宁可说「没开」也不谎报已开）。 */
    fun isServiceEnabled(context: Context): Boolean = runCatching {
        // 用 Class 版 getSystemService 而非常量字符串：API 26 才有 AutofillManager，
        // Class 版重载在 minSdk 26 下不需要任何版本分支。
        context.getSystemService(AutofillManager::class.java)
            ?.hasEnabledAutofillServices() == true
    }.getOrDefault(false)

    /**
     * 跳到系统自动填充设置页。
     *
     * 返回 false 表示这台设备上连那个设置页都打不开（个别 OEM 定制 ROM 会去掉），
     * 调用方据此改成给出手动路径的文案，而不是点一下没反应。
     */
    fun requestEnable(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                // 官方流程只要「action + package: 数据」即可把用户送到本 App 的那一行；
                // 不带 EXTRA_REQUESTED_SERVICE（该常量在 compileSdk 35 的桩里已不可用）。
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** 跳到系统自动填充总设置页（作为打不开请求页时的兜底路径）。 */
    fun openAutofillSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
