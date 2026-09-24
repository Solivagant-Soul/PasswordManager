package com.zz.passwordmanager.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zz.passwordmanager.data.header.HeaderIntegrityException
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.InitializeRequest
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultState
import com.zz.passwordmanager.data.vault.VaultStatus
import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.entries.EntrySummary
import com.zz.passwordmanager.platform.AndroidClipboardBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 初始化/解锁请求（UI 表单 → 用例入参）。 */
data class UnlockFormData(
    val masterPassword: String = "",
    val confirm: String = "",
    val questions: List<Pair<String, String>> = List(3) { "" to "" },
)

/** 明文显隐记忆（FR-9）：离开详情页即移除（T17「明文不残留」）。 */
data class VaultUiState(
    val initialized: Boolean = false,
    val locked: Boolean = true,
    val entries: List<EntrySummary> = emptyList(),
    val query: String = "",
    val revealed: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    /** Header 完整性校验失败（G-B）：数据不可用，UI 据此提供「清除重建」入口。 */
    val vaultCorrupted: Boolean = false,
)

/**
 * M2 状态容器（UDF）：解锁/初始化门禁 → 列表/搜索 → 详情显隐/复制 → 编辑保存。
 * 锁定态由 [VaultSessionManager] 单一事实来源驱动；错误消息经 [VaultUiState.errorMessage]。
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val unlockVault: UnlockVault,
    private val initializeVault: InitializeVault,
    private val destroyVault: DestroyVault,
    private val entryRepository: EntryRepository,
    private val clipboard: AndroidClipboardBridge,
    private val sessionManager: VaultSessionManager,
    status: VaultStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState(initialized = status.isInitialized()))
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        // 锁定以 VaultSessionManager 为单一事实来源：任何路径的锁定（FR-8 自动锁定、
        // 密保找回失败后的锁定等）都要反映到 UI，并清空已解密的数据。
        viewModelScope.launch {
            sessionManager.state.collect { vaultState ->
                // 初值即 Locked，用 !locked 守卫避免初始化时把状态清一遍
                if (vaultState is VaultState.Locked && !_state.value.locked) applyLocked()
            }
        }
    }

    /**
     * 锁定：UI 回到解锁屏，且不保留任何已解密的标题与明文（NFR-S4/T12）。
     *
     * - `revealed` 是明文密码在本项目里唯一的驻留处，必须清；
     * - `details` 不在 [VaultUiState] 内（易漏），必须清；
     * - `entries` 含标题/用户名，属敏感内容，锁定态一律不可见（BR-7）；
     * - `query` 保留：非敏感且是用户意图，解锁后据此重新拉列表更顺手；
     * - `vaultCorrupted` **不动**：损坏态与锁定态正交，清掉会让「清除重建」入口消失。
     */
    private fun applyLocked() {
        details.clear()
        _state.update {
            it.copy(locked = true, entries = emptyList(), revealed = emptyMap(), errorMessage = null)
        }
    }

    // ---- FR-8 自动锁定（由 MainActivity 生命周期驱动） ----

    /** 切到后台：记录时刻。由 `MainActivity.onStop` 调用。 */
    fun onBackground() = sessionManager.onBackground(System.currentTimeMillis())

    /** 回到前台：超期即锁定。档位写死 1 分钟（v1 决策：不做档位 UI 与偏好存储）。 */
    fun onForeground() = sessionManager.onForeground(System.currentTimeMillis(), AUTO_LOCK_DURATION)

    /** 主动锁定（S51「主动锁定立即生效」）：UI 清理交给 collector 统一做。 */
    fun lock() = sessionManager.lock()

    // ---- 初始化 / 解锁（FR-1 / FR-2） ----

    fun initialize(form: UnlockFormData) {
        viewModelScope.launch {
            try {
                initializeVault(
                    InitializeRequest(
                        masterPassword = form.masterPassword.toCharArray(),
                        confirmMasterPassword = form.confirm.toCharArray(),
                        securityQuestions = form.questions.map {
                            InitializeRequest.SecurityQuestionInput(it.first, it.second.toCharArray())
                        },
                    ),
                ).fold(
                    onSuccess = { unlock(form.masterPassword) },
                    onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
                )
            } catch (e: HeaderIntegrityException) {
                // G-B：既有库 Header 已损坏，不进初始化流程（宁严勿松），只提供清除入口
                _state.update { it.copy(vaultCorrupted = true, errorMessage = null) }
            }
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            try {
                unlockVault(password.toCharArray()).fold(
                    onSuccess = { session ->
                        sessionManager.begin(session)
                        _state.update { it.copy(locked = false) }
                        search(_state.value.query)
                    },
                    onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
                )
            } catch (e: HeaderIntegrityException) {
                _state.update { it.copy(vaultCorrupted = true, errorMessage = null) }
            }
        }
    }

    /**
     * 清除并重建密码库（G-B）：**破坏性**，调用方须已完成二次确认。
     * 成功后回到「未初始化」态，用户可重新设置主密码。
     */
    fun destroyCorruptedVault() {
        viewModelScope.launch {
            destroyVault().fold(
                onSuccess = {
                    sessionManager.lock()
                    _state.value = VaultUiState(initialized = false, locked = true)
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
            )
        }
    }

    // ---- 列表 / 搜索（FR-5） ----

    private val details = mutableStateMapOf<String, EntryDetail?>()

    /** 详情/编辑页的数据源：AppNav 经 [detailFor] 观察。 */
    fun detailFor(id: String): EntryDetail? = details[id]

    fun loadDetail(id: String) {
        viewModelScope.launch {
            try {
                details[id] = entryRepository.get(id)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        viewModelScope.launch {
            try {
                val entries = entryRepository.search(query, null, null).first()
                _state.update { it.copy(entries = entries) }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    // ---- 详情 / 显隐 / 复制（FR-9 / T17） ----

    fun reveal(id: String) {
        viewModelScope.launch {
            entryRepository.revealPassword(id).fold(
                onSuccess = { pw -> _state.update { it.copy(revealed = it.revealed + (id to pw.concatToString())) } },
                onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
            )
        }
    }

    fun hide(id: String) {
        _state.update { it.copy(revealed = it.revealed - id) } // 离开详情页/手动隐藏（明文不残留）
    }

    fun copyPassword(id: String) {
        viewModelScope.launch {
            entryRepository.revealPassword(id).fold(
                onSuccess = { pw -> clipboard.copySensitive(pw.concatToString()) },
                onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
            )
        }
    }

    // ---- 编辑 / 删除（FR-3） ----

    fun save(id: String?, input: EntryInput, onSaved: () -> Unit) {
        viewModelScope.launch {
            val result = if (id == null) {
                entryRepository.create(input)
            } else {
                entryRepository.update(id, input)
            }
            result.fold(
                onSuccess = {
                    refresh()
                    onSaved()
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            entryRepository.delete(id).fold(
                onSuccess = { refresh() },
                onFailure = { e -> _state.update { it.copy(errorMessage = e.message) } },
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun refresh(query: String = _state.value.query) = search(query)

    private companion object {
        /** FR-8 档位写死 1 分钟（2026-09-24 决策：不做档位 UI 与偏好存储，档位选择归二期）。 */
        val AUTO_LOCK_DURATION = AutoLockDuration.M1
    }
}
