package com.zz.passwordmanager.platform.integrity

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「怎么查」的那半边：把 [RootEnvironmentFacts] 采出来。**只做采集，不做判断。**
 *
 * 抽成接口是为了让 [RootDetection] 那一半能在没有 root 的机器上被完整测试；
 * 本实现本身不做任何判断，也就没有任何「判定为 root 之后该做什么」的代码路径——
 * 报告出了 `:app` 的 UI 就结束，`:app` 那边除了显示文案之外不读它做任何分支。
 */
interface RootEnvironmentProbe {
    suspend fun probe(): RootEnvironmentFacts
}

/**
 * 真机探测。
 *
 * **成本**：约 26 次 `stat`（[RootDetection.SU_PATHS] 13 + BUSYBOX 7 + MAGISK 6）+
 * 5 次 `access(W_OK)` + 10 次定向 `getPackageInfo`。全在 [Dispatchers.IO] 上，
 * 不占主线程；实测毫秒量级。因此**不放在解锁路径上**——见 [RootDetection] 的调用方
 * （`:app` 仅在用户进入设置 Tab 或打开子页时触发一次）。
 *
 * 整段包在 [withContext] 里而非让调用方选调度器，与项目「数据层/平台层自持调度器」的约定一致。
 */
class AndroidRootEnvironmentProbe(context: Context) : RootEnvironmentProbe {

    private val appContext = context.applicationContext

    override suspend fun probe(): RootEnvironmentFacts = withContext(Dispatchers.IO) {
        val probed = RootDetection.SU_PATHS +
            RootDetection.BUSYBOX_PATHS +
            RootDetection.MAGISK_PATHS
        RootEnvironmentFacts(
            existingPaths = probed.filter { File(it).exists() }.toSet(),
            installedPackages = RootDetection.ROOT_MANAGER_PACKAGES.filter { isInstalled(it) }.toSet(),
            writablePaths = RootDetection.WRITABLE_SYSTEM_PATHS.filter { File(it).canWrite() }.toSet(),
            buildTags = Build.TAGS,
            roDebuggable = systemProperty("ro.debuggable"),
            roSecure = systemProperty("ro.secure"),
        )
    }

    /**
     * 定向查询单个包是否可解析。
     *
     * 逐个查而非 `getInstalledPackages` 拉全量：后者在这个场景里既慢又没必要。
     * `NameNotFoundException` 混着「API 30+ 看不见」两种情况（本 App 未声明 `<queries>`），
     * 二者都归为「没装」——如 [RootDetection.ROOT_MANAGER_PACKAGES] 的注释所述，
     * 这是**已记录在案的局限**，不是可以在此"修掉"的东西。
     */
    private fun isInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * 读系统属性。`android.os.SystemProperties` 是非 SDK 接口，反射在 API 28+ 可能被拦。
     * 失败返回 null → [RootObservation] 记为「未知」，不猜。
     */
    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()
}
