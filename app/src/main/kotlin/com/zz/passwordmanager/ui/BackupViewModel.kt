package com.zz.passwordmanager.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zz.passwordmanager.data.backup.BackupFileStore
import com.zz.passwordmanager.data.backup.ExportBackup
import com.zz.passwordmanager.data.backup.ImportBackup
import com.zz.passwordmanager.data.backup.SafBackupFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 待用户确认的导入（FR-7 / S2）。
 *
 * **只带 Uri 与两个数字**：预检解出来的明文一个字节都不往这里放，也不往 ViewModel 里放。
 * 这是 `ImportBackup.inspect` 存在的全部理由——UI 要做的只是显示「这份备份有 N 条」，
 * 而做到这一点不需要把全库明文搬进 UI 层的内存。
 */
data class PendingImport(
    val uri: Uri,
    val entryCount: Int,
    val categoryCount: Int,
)

data class BackupUiState(
    /** 导出/导入进行中。期间禁用所有按钮，避免重复触发（每次都要跑 Argon2）。 */
    val busy: Boolean = false,
    /** 已预检、等待用户确认破坏性替换；null = 当前没有待确认的导入。 */
    val pendingImport: PendingImport? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
)

/**
 * SAF 读写端点的工厂。
 *
 * 抽出来而不是让 [BackupViewModel] 直接 `new SafBackupFileStore(...)`：`:app` 没有
 * `src/androidTest`，而 SAF 那一步要在 `Dispatchers.IO` 上真读真写 `ContentResolver`，
 * JVM 单测里既跑不动（`Looper` 未 mock）也不该真跑。留一个可替换的构造点，
 * 编排逻辑（预检 → 确认 → 提交）才有机会被自动测到。
 *
 * 暂存目录放 `cacheDir` 而非 `filesDir`：那里存的是**已定稿的密文**，
 * 系统可在空间紧张时回收，且不属于用户可见的私有数据。
 */
interface BackupStoreFactory {
    operator fun invoke(uri: Uri): BackupFileStore
}

/** 生产实现：SAF。 */
class SafBackupStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupStoreFactory {
    override fun invoke(uri: Uri): BackupFileStore =
        SafBackupFileStore(context.contentResolver, uri, File(context.cacheDir, "backup"))
}

/**
 * FR-7 备份的 UI 状态容器。
 *
 * **单列一个 ViewModel 而不是塞进 [VaultViewModel]**：后者已经 721 行，且备份是
 * 另一个入口、另一套生命周期（见 `BackupVault` 的 KDoc）。合进去只会让两件事互相淹没。
 *
 * ### 主密码从哪来
 * 备份密钥由主密码派生（`BackupKeyDeriver`），而解锁后的会话里**只有 DEK、没有主密码**
 * ——这是 NFR-S4 的题中之义。因此每次导出/导入都由 UI 现问一次主密码，
 * 本类不缓存它，也不把它写进 [BackupUiState]（state 会被快照、被日志、被测试读）。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val exportBackup: ExportBackup,
    private val importBackup: ImportBackup,
    private val stores: BackupStoreFactory,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** 导出到 SAF 选定的位置。返回的条目数用于给用户一句明确的反馈。 */
    fun exportTo(uri: Uri, masterPassword: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            exportBackup.export(masterPassword.toCharArray(), stores(uri)).fold(
                onSuccess = { count ->
                    _state.update {
                        it.copy(busy = false, successMessage = "已导出 $count 条条目，备份文件已加密")
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(busy = false, errorMessage = backupMessage(e)) }
                },
            )
        }
    }

    /**
     * 导入第一步：预检，只回报条目数。
     *
     * 失败即导入失败——口令错、文件坏、版本不认识、条目不合规都在这里返回，
     * 此时密码库一个字节都没被碰过（见 `ImportBackup` 的两段式说明）。
     */
    fun inspectImport(uri: Uri, masterPassword: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null, pendingImport = null) }
        viewModelScope.launch {
            val result = runCatching { stores(uri).read() }
                .fold(
                    onSuccess = { bytes -> importBackup.inspect(bytes, masterPassword.toCharArray()) },
                    onFailure = { Result.failure(it) },
                )
            result.fold(
                onSuccess = { preview ->
                    _state.update {
                        it.copy(
                            busy = false,
                            pendingImport = PendingImport(uri, preview.entryCount, preview.categoryCount),
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(busy = false, errorMessage = backupMessage(e)) }
                },
            )
        }
    }

    /**
     * 导入第二步：整库替换。**破坏性**，调用方必须已拿到用户对条目数的明确确认。
     *
     * 刻意不复用上一步的明文：`apply` 从原始密文**重新走一遍**预检再提交，
     * 代价是一次 Argon2（约 1 秒），换来 ViewModel 里一个明文字节都不必存。
     */
    fun confirmImport(masterPassword: String) {
        val pending = _state.value.pendingImport ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                val bytes = stores(pending.uri).read()
                importBackup.apply(bytes, masterPassword.toCharArray())
            }.fold(onSuccess = { it }, onFailure = { Result.failure(it) })

            result.fold(
                onSuccess = { count ->
                    _state.update {
                        it.copy(
                            busy = false,
                            pendingImport = null,
                            successMessage = "已导入 $count 条条目，原有内容已被全部替换",
                        )
                    }
                },
                onFailure = { e ->
                    // 预检这次没过：确认框还留着，用户可以改口令重试或取消
                    _state.update { it.copy(busy = false, errorMessage = backupMessage(e)) }
                },
            )
        }
    }

    /** 用户放弃这次导入。预检解出的明文此时早已在 `inspect` 内部清零，这里只丢状态。 */
    fun cancelImport() {
        _state.update { it.copy(pendingImport = null, errorMessage = null) }
    }

    /**
     * SAF 不可用（设备上没有任何可处理该 Intent 的 DocumentsProvider）。
     *
     * 如实上报而不是静默无反应：Activity 的 `launch()` 在无提供方时抛
     * `ActivityNotFoundException`，吞掉它只会让用户对着一个点了没反应的按钮发呆。
     */
    fun reportSafUnavailable(operation: String) {
        _state.update {
            it.copy(
                errorMessage = "本机没有可用的文件选择器，$operation 无法完成。" +
                    "请在系统设置里安装或启用「文件」应用后重试。",
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissSuccess() {
        _state.update { it.copy(successMessage = null) }
    }
}
