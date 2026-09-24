package com.zz.passwordmanager.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zz.passwordmanager.data.biometric.BiometricChallenge
import com.zz.passwordmanager.data.biometric.BiometricToggle
import com.zz.passwordmanager.data.biometric.UnlockWithBiometric
import com.zz.passwordmanager.data.header.HeaderIntegrityException
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.ChangeMasterPassword
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.InitializeRequest
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.ReadSecurityQuestions
import com.zz.passwordmanager.data.vault.ResetVault
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultState
import com.zz.passwordmanager.data.vault.VaultStatus
import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.biometric.BiometricGate
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.entries.EntrySummary
import com.zz.passwordmanager.platform.AndroidClipboardBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.crypto.Cipher
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
    /** 成功提示（如「主密码已重置，请用新密码解锁」）。 */
    val successMessage: String? = null,
    /** Header 完整性校验失败（G-B）：数据不可用，UI 据此提供「清除重建」入口。 */
    val vaultCorrupted: Boolean = false,
    /** FR-12 密保找回表单模式：解锁屏切换为「答密保 + 重设主密码」。 */
    val recovering: Boolean = false,
    /** 密保问题文本（仅问题，答案从不落盘 D4）。 */
    val recoveryQuestions: List<String> = emptyList(),
    /** FR-2 生物识别是否已开启（设置页开关读它；不含硬件就绪判断）。 */
    val biometricEnabled: Boolean = false,
    /** FR-2 生物识别快捷解锁入口是否可用（已开启且硬件就绪）。 */
    val biometricAvailable: Boolean = false,
    /** 认证进行中：避免重复点击弹出多个提示框。 */
    val biometricAuthenticating: Boolean = false,
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
    private val resetVault: ResetVault,
    private val changeMasterPassword: ChangeMasterPassword,
    private val readSecurityQuestions: ReadSecurityQuestions,
    private val unlockWithBiometric: UnlockWithBiometric,
    private val biometricToggle: BiometricToggle,
    private val gate: BiometricGate,
    private val entryRepository: EntryRepository,
    private val clipboard: AndroidClipboardBridge,
    private val sessionManager: VaultSessionManager,
    private val status: VaultStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState(initialized = status.isInitialized()))
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        refreshBiometricAvailability()
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

    // ---- FR-12 密保找回（V1-2） ----

    /** 进入找回模式：展示密保问题文本（不含答案）。 */
    fun beginRecovery() {
        viewModelScope.launch {
            try {
                val questions = readSecurityQuestions()
                _state.update {
                    it.copy(
                        recovering = true,
                        recoveryQuestions = questions,
                        errorMessage = null,
                    )
                }
            } catch (e: HeaderIntegrityException) {
                _state.update { it.copy(vaultCorrupted = true, errorMessage = null) }
            }
        }
    }

    fun cancelRecovery() {
        _state.update { it.copy(recovering = false, recoveryQuestions = emptyList(), errorMessage = null) }
    }

    /**
     * 答对 3 道密保问题后重置主密码（BR-6：原主密码不可取回，只能重置）。
     * 成功后回到普通解锁态，提示用新密码解锁。
     */
    fun recoverVault(
        answers: List<CharArray>,
        newPassword: String,
        confirmNewPassword: String,
    ) {
        viewModelScope.launch {
            resetVault(answers, newPassword.toCharArray(), confirmNewPassword.toCharArray()).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            recovering = false,
                            recoveryQuestions = emptyList(),
                            errorMessage = null,
                            successMessage = "主密码已重置，请用新密码解锁",
                        )
                    }
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    // ---- FR-2 生物识别快捷解锁（V1-4） ----

    /**
     * 开始生物识别解锁第一段：资格判定。UI 据此决定是否弹提示框。
     * 认证本身（BiometricPrompt）由 :app 的 [BiometricAuthenticator] 驱动，
     * 因为它需要 Activity 上下文。
     *
     * suspend 而非 runBlocking——用例内部已切到 IO 调度器，调用方在 Main。
     */
    suspend fun beginBiometricUnlock(): Result<BiometricChallenge> {
        _state.update { it.copy(biometricAuthenticating = true, errorMessage = null) }
        val result = unlockWithBiometric.prepare()
        result.onFailure { finishBiometric(it) }
        return result
    }

    /** 认证成功后完成解锁：用**已认证**的 cipher 解出 DEK 并建立会话。 */
    fun completeBiometricUnlock(challenge: BiometricChallenge, authenticatedCipher: Cipher) {
        viewModelScope.launch {
            unlockWithBiometric(challenge, authenticatedCipher).fold(
                onSuccess = { session ->
                    sessionManager.begin(session)
                    _state.update { it.copy(biometricAuthenticating = false) }
                    search(_state.value.query)
                },
                onFailure = { e -> finishBiometric(e) },
            )
        }
    }

    /** UI 侧认证失败/取消：按 [AuthError] 决定静默降级还是提示。 */
    fun onBiometricAuthError(error: AuthError) {
        when (error) {
            // 取消是用户自己的选择，不该弹错误训斥；主密码入口始终保留（生物识别只是加速器）
            AuthError.BiometricCancelled -> _state.update { it.copy(biometricAuthenticating = false) }
            else -> finishBiometric(AuthException(error))
        }
    }

    private fun finishBiometric(e: Throwable) {
        _state.update { it.copy(biometricAuthenticating = false, errorMessage = userMessage(e)) }
        refreshBiometricAvailability()
    }

    /**
     * 重算快捷解锁入口的可见性：在 init、每次回到锁定态、以及生物识别失败后重算——
     * 用户可能刚在系统设置里改了录入情况（新增指纹会使旧 Keystore 密钥永久失效）。
     */
    fun refreshBiometricAvailability() {
        val enabled = status.isBiometricEnabled()
        val available = enabled && gate.availability() == BiometricGate.Availability.AVAILABLE
        _state.update { it.copy(biometricEnabled = enabled, biometricAvailable = available) }
    }

    // ---- P-03 生物识别开关（开启为两段式，见 BiometricToggle 的 KDoc） ----

    /** 关闭：验主密码后清除。 */
    fun disableBiometric(masterPassword: String) {
        viewModelScope.launch {
            biometricToggle.disable(masterPassword.toCharArray()).fold(
                onSuccess = { refreshBiometricAvailability() },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    /**
     * 开启第一段：验主密码 + 硬件门禁。成功后 UI 需弹出 `BiometricPrompt`，
     * 再调 [completeBiometricEnable] 传入**已认证**的加密 cipher。
     *
     * 返回待包裹的 DEK 句柄；UI 在认证成功、失败或取消时都必须调用 [clearPendingBiometricEnable]。
     */
    suspend fun prepareBiometricEnable(masterPassword: String): BiometricToggle.EnableSetup? {
        _state.update { it.copy(errorMessage = null) }
        val setup = biometricToggle.prepareEnable(masterPassword.toCharArray()).getOrElse { e ->
            _state.update { it.copy(errorMessage = userMessage(e)) }
            return null
        }
        pendingBiometricEnable = setup
        return setup
    }

    /** 开启第二段：包裹并落盘。 */
    fun completeBiometricEnable(authenticatedCipher: Cipher) {
        val setup = pendingBiometricEnable
        pendingBiometricEnable = null
        if (setup == null) return
        viewModelScope.launch {
            biometricToggle.completeEnable(setup, authenticatedCipher).fold(
                onSuccess = {
                    setup.clear()
                    refreshBiometricAvailability()
                },
                onFailure = { e ->
                    setup.clear()
                    _state.update { it.copy(errorMessage = userMessage(e)) }
                    refreshBiometricAvailability()
                },
            )
        }
    }

    /** 认证被取消/失败：清零暂存的 DEK，不落任何东西。 */
    fun clearPendingBiometricEnable() {
        pendingBiometricEnable?.clear()
        pendingBiometricEnable = null
        _state.update { it.copy(biometricAuthenticating = false) }
    }

    // ---- P-07 修改主密码（V1-3） ----

    // 方法名刻意不同于注入的 use case 属性名，否则同名词会被解析为自身（无限递归）
    fun submitMasterPasswordChange(
        currentPassword: String,
        newPassword: String,
        confirmNewPassword: String,
        onChanged: () -> Unit,
    ) {
        viewModelScope.launch {
            changeMasterPassword(
                currentPassword.toCharArray(),
                newPassword.toCharArray(),
                confirmNewPassword.toCharArray(),
            ).fold(
                onSuccess = {
                    _state.update { it.copy(successMessage = "主密码已修改") }
                    onChanged()
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

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
                    onSuccess = {
                        // initialized 原本只在构造时读一次 Header；首次创建后必须置位，
                        // 否则 AppNav 仍按「未初始化」渲染创建表单（设备走查才发现）
                        _state.update { it.copy(initialized = true) }
                        unlock(form.masterPassword)
                    },
                    onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
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
                    onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
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
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    // ---- 列表 / 搜索（FR-5） ----

    private val details = mutableStateMapOf<String, EntryDetail?>()
    private var pendingBiometricEnable: BiometricToggle.EnableSetup? = null

    /** 详情/编辑页的数据源：AppNav 经 [detailFor] 观察。 */
    fun detailFor(id: String): EntryDetail? = details[id]

    fun loadDetail(id: String) {
        viewModelScope.launch {
            try {
                details[id] = entryRepository.get(id)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = userMessage(e)) }
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
                _state.update { it.copy(errorMessage = userMessage(e)) }
            }
        }
    }

    // ---- 详情 / 显隐 / 复制（FR-9 / T17） ----

    fun reveal(id: String) {
        viewModelScope.launch {
            entryRepository.revealPassword(id).fold(
                onSuccess = { pw -> _state.update { it.copy(revealed = it.revealed + (id to pw.concatToString())) } },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
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
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
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
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            entryRepository.delete(id).fold(
                onSuccess = { refresh() },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
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
