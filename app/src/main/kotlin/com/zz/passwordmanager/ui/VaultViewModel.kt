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
import com.zz.passwordmanager.data.entries.CategoryRepository
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.preferences.UserPreferences
import com.zz.passwordmanager.domain.security.SecurityScanner
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.domain.entries.EntryInput
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.entries.EntrySummary
import com.zz.passwordmanager.domain.password.GeneratorOptions
import com.zz.passwordmanager.domain.password.PasswordGenerator
import com.zz.passwordmanager.domain.password.PasswordGenerator.LENGTH_MAX
import com.zz.passwordmanager.domain.password.PasswordGenerator.LENGTH_MIN
import com.zz.passwordmanager.platform.AndroidClipboardBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.crypto.Cipher
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** FR-8 自动锁定档位（T40/v2.1 F1）：移入状态，设置页点一下即重组。 */
    val autoLockDuration: AutoLockDuration = AutoLockDuration.M1,
    /** Header 完整性校验失败（G-B）：数据不可用，UI 据此提供「清除重建」入口。 */
    val vaultCorrupted: Boolean = false,
    /** FR-12 密保找回表单模式：解锁屏切换为「答密保 + 重设主密码」。 */
    val recovering: Boolean = false,
    /** 密保问题文本（仅问题，答案从不落盘 D4）。 */
    val recoveryQuestions: List<String> = emptyList(),
    /** 安全扫描中。 */
    val scanning: Boolean = false,
    /** 最近一次扫描结果（FR-13）。null = 尚未扫描。 */
    val securityReport: com.zz.passwordmanager.domain.security.SecurityReport? = null,
    /** 默认密码长度（T41/FR-16）：生成器初值，设置项可改。 */
    val defaultPasswordLength: Int = 16,
    /** v2.1/D4：生成器选项（长度之外的四个字符集开关 + 排除易混淆），全局共享。 */
    val generatorOptions: GeneratorOptions = GeneratorOptions(),
    /** 全部分类（列表 chips 用）。 */
    val categories: List<Category> = emptyList(),
    /** 当前分类筛选（null = 全部）。 */
    val categoryFilter: String? = null,
    /** 仅看收藏。 */
    val favoritesOnly: Boolean = false,
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
    private val categoryRepository: CategoryRepository,
    private val securityScanner: SecurityScanner,
    private val preferences: UserPreferences,
    private val clipboard: AndroidClipboardBridge,
    private val sessionManager: VaultSessionManager,
    private val status: VaultStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState(initialized = status.isInitialized()))
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        // 订阅偏好：自动锁定档位与生成器默认长度都「改了立刻生效」
        viewModelScope.launch {
            preferences.autoLockDuration.collect { duration ->
                _state.update { it.copy(autoLockDuration = duration) }
            }
        }
        viewModelScope.launch {
            preferences.defaultPasswordLength.collect { length ->
                // v2.1：上界由 64 收到 32，但升级用户的 DataStore 里可能仍存着越界值。
                // 在状态边界收敛一次，后续所有消费方（生成器页、编辑页一键生成）都拿到合法长度，
                // 不会各自重复 coerce、也不会因 LENGTH_OUT_OF_RANGE 静默失败。
                _state.update { it.copy(defaultPasswordLength = length.coerceIn(LENGTH_MIN, LENGTH_MAX)) }
            }
        }
        viewModelScope.launch {
            preferences.generatorOptions.collect { options ->
                _state.update { it.copy(generatorOptions = options) }
            }
        }
        refreshBiometricAvailability()
        // 锁定以 VaultSessionManager 为单一事实来源：任何路径的锁定（FR-8 自动锁定、
        // 密保找回失败后的锁定等）都要反映到 UI，并清空已解密的数据。
        viewModelScope.launch {
            // locked **只由这里派生**（VaultSessionManager 是单一事实来源）。
            // 此前两条解锁路径各自手动写 `locked = false`，而生物识别那条漏了，
            // 结果指纹验过了却不进首页（v0.10 实机踩坑）。收敛到单一写点。
            sessionManager.state.collect { vaultState ->
                when (vaultState) {
                    // 初值即 Locked，用 !locked 守卫避免初始化时把状态清一遍
                    is VaultState.Locked -> if (!_state.value.locked) applyLocked()
                    // 成功解锁时顺带清掉 errorMessage：紧邻其上一次失败的尝试（例如主密码先输错一次）
                    // 会把「主密码错误，还可尝试 N 次」留在 state 里，而 applyLocked() 跑在锁定时、
                    // dismissError() 这条路径上无人调用，于是它会渲染在已解锁的列表页上。
                    // 收在这里而不是各处 onSuccess：三条解锁路径（主密码 / 生物识别 / 密保重置）
                    // 都要走 sessionManager.begin()，此分支是它们唯一的汇合点，修复不会随路径漂移。
                    is VaultState.Unlocked -> _state.update { it.copy(locked = false, errorMessage = null) }
                }
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
        listJob?.cancel() // 锁定即停止订阅，不再从库里取数
        details.clear()
        _state.update {
            it.copy(locked = true, entries = emptyList(), revealed = emptyMap(), errorMessage = null)
        }
    }

    // ---- FR-8 自动锁定（由 MainActivity 生命周期驱动） ----

    /** 切到后台：记录时刻。由 `MainActivity.onStop` 调用。 */
    fun onBackground() = sessionManager.onBackground(System.currentTimeMillis())

    /**
     * 回到前台：超期即锁定。档位取自用户偏好（T40/FR-16，默认 1 分钟）。
     * 订阅式读取——设置里改了档位立即生效，不必重启。
     */
    fun onForeground() = sessionManager.onForeground(System.currentTimeMillis(), _state.value.autoLockDuration)

    // ---- 用户偏好（T39–T41） ----

    fun setAutoLockDuration(duration: AutoLockDuration) {
        // 状态由上面的 collector 从偏好流回写；这里只负责落盘
        viewModelScope.launch { preferences.setAutoLockDuration(duration) }
    }

    fun setDefaultPasswordLength(length: Int) {
        viewModelScope.launch { preferences.setDefaultPasswordLength(length) }
    }

    fun setGeneratorOptions(options: GeneratorOptions) {
        viewModelScope.launch { preferences.setGeneratorOptions(options) }
    }

    /**
     * 按**当前全局生成器选项**直接产出一个密码（v2.1/D4：编辑页零弹窗）。
     * 字符集全关时返回 null——UI 须给出可见提示，而不是静默无反应。
     */
    fun generatePasswordNow(): String? {
        val options = _state.value.generatorOptions
        if (options.charSets().isEmpty()) return null
        return PasswordGenerator.generate(options.rules(_state.value.defaultPasswordLength))
            .getOrNull()?.concatToString()
    }

    // ---- 主动锁定 ----

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
                    sessionManager.begin(session) // locked 由 collector 派生
                    _state.update { it.copy(biometricAuthenticating = false) }
                    startObservingEntries()
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
                        sessionManager.begin(session) // locked 由 collector 派生
                        startObservingEntries()
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
    private var listJob: Job? = null

    /** 详情/编辑页的数据源：AppNav 经 [detailFor] 观察。 */
    fun detailFor(id: String): EntryDetail? = details[id]

    fun loadDetail(id: String) {
        viewModelScope.launch {
            try {
                val detail = entryRepository.get(id)
                // 锁定与本次读取并发时，applyLocked() 已清空 details；
                // 不守卫就会把已解密内容写回锁定态，违反 BR-7「锁定态一律不可见」
                if (_state.value.locked) return@launch
                details[id] = detail
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = userMessage(e)) }
            }
        }
    }

    /**
     * 搜索条件变更（T48/FR-18）。
     *
     * 只改条件，不直接查库——列表由 [startObservingEntries] 订阅的 Flow 驱动。
     */
    fun search(query: String) = queryChanged(query)

    /**
     * 分类 chips 选中；再点同一档则取消筛选（回到「全部」）。
     * 「收藏」与分类互斥——单选一档（需求 §6.5）。
     */
    fun filterByCategory(categoryId: String?) {
        val s = _state.value
        val next = if (!s.favoritesOnly && s.categoryFilter == categoryId) null else categoryId
        _state.update { it.copy(categoryFilter = next, favoritesOnly = false) }
        applyFilters()
    }

    /** 「收藏」档：再点一次取消。与分类互斥。 */
    fun filterByFavoritesOnly() {
        val next = !_state.value.favoritesOnly
        _state.update { it.copy(favoritesOnly = next, categoryFilter = null) }
        applyFilters()
    }

    /** 搜索输入变化时保留当前筛选档。 */
    fun queryChanged(query: String) {
        _state.update { it.copy(query = query) }
        applyFilters()
    }

    private fun applyFilters() {
        val s = _state.value
        // 「收藏」是独立筛选档：与分类互斥，且不是 DAO 条件——
        // 收藏是 entries 的一列，但 chips 语义上与分类并列，故单独处理
        val categoryId = if (s.favoritesOnly) null else s.categoryFilter
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                entryRepository.search(s.query, categoryId).collect { rows ->
                    _state.update {
                        val filtered = if (s.favoritesOnly) rows.filter { it.favorite } else rows
                        it.copy(entries = filtered)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = userMessage(e)) }
            }
        }
    }

    /** 加载分类列表（解锁后调用，chips 需要）。 */
    private fun loadCategories() {
        viewModelScope.launch {
            runCatching { categoryRepository.list() }
                .onSuccess { list -> _state.update { it.copy(categories = list) } }
                .onFailure { e -> _state.update { it.copy(errorMessage = userMessage(e)) } }
        }
    }

    /**
     * 切换收藏（FR-5）。列表与详情两处入口共用；
     * 列表由 Room Flow 自动重发，但详情页的数据源是 [details]，不重载不会刷新（F3）。
     */
    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch {
            entryRepository.setFavorite(id, favorite).fold(
                // 详情页数据源是 details 而非列表 Flow，不重载的话星标不会翻转（F3）
                onSuccess = { loadDetail(id) },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    // ---- 安全中心（T42/T43） ----

    /**
     * 执行本地安全扫描（FR-13）。全程离线；解密出的明文在扫描函数内即清零，
     * 结果只带条目 id（见 `DefaultSecurityScanner` 的明文纪律）。
     */
    fun startScan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { securityScanner.scan() }
                .onSuccess { report -> _state.update { it.copy(scanning = false, securityReport = report) } }
                .onFailure { e -> _state.update { it.copy(scanning = false, errorMessage = userMessage(e)) } }
        }
    }

    // ---- 分类管理（T34） ----

    fun upsertCategory(id: String?, name: String) {
        viewModelScope.launch {
            val newId = id ?: java.util.UUID.randomUUID().toString()
            categoryRepository.upsert(newId, name).fold(
                onSuccess = { loadCategories() },
                onFailure = { e -> _state.update { it.copy(errorMessage = categoryMessage(e)) } },
            )
        }
    }

    fun deleteCategory(id: String) {
        viewModelScope.launch {
            categoryRepository.delete(id).fold(
                onSuccess = {
                    // 当前正在看被删的分类 → 回到「全部」，否则 chips 选中态悬空
                    if (_state.value.categoryFilter == id) {
                        _state.update { it.copy(categoryFilter = null) }
                    }
                    loadCategories()
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = categoryMessage(e)) } },
            )
        }
    }

    /**
     * 启动列表订阅（T48）。在解锁成功后调用——列表由 Room Flow 驱动，
     * 增删改条目会自动反映，无需手动 refresh。
     */
    private fun startObservingEntries() {
        applyFilters()
        loadCategories()
    }

    // ---- 详情 / 显隐 / 复制（FR-9 / T17） ----

    fun reveal(id: String) {
        viewModelScope.launch {
            entryRepository.revealPassword(id).fold(
                onSuccess = { pw ->
                    // 锁定与本次揭示并发时，applyLocked() 已清空 revealed；
                    // 不守卫就会把明文密码写回锁定态，违反 T17「明文不残留」/ NFR-S4
                    if (_state.value.locked) return@launch
                    _state.update { it.copy(revealed = it.revealed + (id to pw.concatToString())) }
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    fun hide(id: String) {
        _state.update { it.copy(revealed = it.revealed - id) } // 离开详情页/手动隐藏（明文不残留）
    }

    /** 复制生成器产出的密码（走同一套剪贴板 30 秒清理，FR-4「可复制」）。 */
    fun copyGeneratedPassword(password: String) {
        clipboard.copySensitive(password)
        _state.update { it.copy(successMessage = COPY_FEEDBACK) }
    }

    fun copyPassword(id: String) {
        viewModelScope.launch {
            entryRepository.revealPassword(id).fold(
                onSuccess = { pw ->
                    clipboard.copySensitive(pw.concatToString())
                    _state.update { it.copy(successMessage = COPY_FEEDBACK) }
                },
                onFailure = { e -> _state.update { it.copy(errorMessage = userMessage(e)) } },
            )
        }
    }

    /** 复制非密码字段（用户名等）——同样走 30 秒自动清理并给反馈。 */
    fun copyPlainText(value: String) {
        if (value.isEmpty()) return
        clipboard.copySensitive(value)
        _state.update { it.copy(successMessage = COPY_FEEDBACK) }
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

    /** 清掉一次性成功提示（Snackbar 展示完毕后由 UI 调用）。 */
    fun dismissSuccess() {
        _state.update { it.copy(successMessage = null) }
    }

    private fun refresh(query: String = _state.value.query) = search(query)

    companion object {
        /** 复制反馈文案：与剪贴板 30 秒自动清理（FR-4）保持一致，别让用户以为粘不出来。 */
        const val COPY_FEEDBACK = "已复制，30 秒后自动清除"
    }
}
