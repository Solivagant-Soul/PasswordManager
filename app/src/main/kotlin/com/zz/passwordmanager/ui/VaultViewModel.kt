package com.zz.passwordmanager.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zz.passwordmanager.data.vault.InitializeRequest
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultStatus
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
)

/**
 * M2 状态容器（UDF）：解锁/初始化门禁 → 列表/搜索 → 详情显隐/复制 → 编辑保存。
 * 锁定态由 [VaultSessionManager] 单一事实来源驱动；错误消息经 [VaultUiState.errorMessage]。
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val unlockVault: UnlockVault,
    private val initializeVault: InitializeVault,
    private val entryRepository: EntryRepository,
    private val clipboard: AndroidClipboardBridge,
    private val sessionManager: VaultSessionManager,
    status: VaultStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState(initialized = status.isInitialized()))
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    // ---- 初始化 / 解锁（FR-1 / FR-2） ----

    fun initialize(form: UnlockFormData) {
        viewModelScope.launch {
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
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            unlockVault(password.toCharArray()).fold(
                onSuccess = { session ->
                    sessionManager.begin(session)
                    _state.update { it.copy(locked = false) }
                    search(_state.value.query)
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
}
