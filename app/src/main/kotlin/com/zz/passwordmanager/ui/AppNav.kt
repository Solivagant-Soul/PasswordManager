package com.zz.passwordmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zz.passwordmanager.AppApplication
import com.zz.passwordmanager.R
import com.zz.passwordmanager.domain.entries.EntryDetail
import com.zz.passwordmanager.platform.biometric.BiometricDiagnostics
import kotlinx.coroutines.launch

/** 手写轻量导航（延续 T15 的「不引 navigation-compose」决策）：每个 Tab 一个回退栈。 */
sealed interface Screen {
    data object List : Screen
    data class Detail(val entryId: String) : Screen
    data class Edit(val entryId: String?) : Screen
    data object Settings : Screen
    data object Generator : Screen
    data object Security : Screen
    data object About : Screen

    // 设置子页（v2.1/§4）：只压入 SETTINGS 栈，返回键逐级回退
    data object SettingAutoLock : Screen
    data object SettingBiometric : Screen
    data object SettingDefaultLength : Screen
    data object SettingCategories : Screen
    data object SettingChangePassword : Screen
}

/**
 * 底部四个一级页（设计稿 v0.8）。每个 Tab 有一个根页，点 Tab 一律回到它
 * （见 AppNav 内 `selectTab` 处的注释）；`root` 就是那个根页。
 */
enum class AppTab(val label: String, val iconRes: Int, val root: Screen) {
    PASSWORDS("密码", R.drawable.ic_key, Screen.List),
    GENERATOR("生成", R.drawable.ic_casino, Screen.Generator),
    SECURITY("安全", R.drawable.ic_shield, Screen.Security),
    SETTINGS("设置", R.drawable.ic_settings, Screen.Settings),
}

@Composable
fun AppNav(viewModel: VaultViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val appContext = LocalContext.current
    val versionName = remember(appContext) {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    // 每个 Tab 各自持有回退栈
    val stacks = remember {
        mapOf(
            AppTab.PASSWORDS to mutableStateListOf<Screen>(Screen.List),
            AppTab.GENERATOR to mutableStateListOf<Screen>(Screen.Generator),
            AppTab.SECURITY to mutableStateListOf<Screen>(Screen.Security),
            AppTab.SETTINGS to mutableStateListOf<Screen>(Screen.Settings),
        )
    }
    var currentTab by remember { mutableStateOf(AppTab.PASSWORDS) }

    // 点 Tab 一律回到该 Tab 的根页（覆盖 T44 的「切 Tab 不清栈」）。
    // 用户的原话是「点了新建 → 点设置 → 再点密码，应该显示所有密码的界面」：
    // 那套「恢复原位置」的语义在自用场景里反而制造困惑（半路丢下的新建表单还在栈里）。
    // 已经身处该 Tab 时也重置——这正是主流 App 的行为，兼作「回到顶部/回根页」的出口。
    fun selectTab(tab: AppTab) {
        currentTab = tab
        stacks.getValue(tab).apply {
            clear()
            add(tab.root)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(state.successMessage, state.locked) {
        // 锁定态不消费：此时 successMessage 归解锁屏内联渲染
        // （UnlockScreen.kt 里有同一份 state.successMessage 的 Text），
        // 「主密码已重置」这条提示恰恰必须留到用户看见；
        // 若 Snackbar 也消费它，同一条消息会出现两次且被提前清空。
        //
        // locked 也要进 key：successMessage 是在 locked == true 时被设置的，
        // 只监听字符串的话，解锁那一刻若有待消费的消息不会重新触发，提示会静默丢失。
        if (state.locked) return@LaunchedEffect
        val message = state.successMessage ?: return@LaunchedEffect
        // 直接 await，不套 snackbarScope.launch：后者属于 rememberCoroutineScope 的 job，
        // key 变化不会取消它——锁定发生在 Snackbar 展示期间时，
        // 飞行中的协程仍会把 Snackbar 画到解锁屏上并清掉消息。
        snackbarHostState.showSnackbar(message)
        viewModel.dismissSuccess()
    }
    val backStack: SnapshotStateList<Screen> = stacks.getValue(currentTab)
    val top = backStack.last()

    // FR-8/BR-7：锁定门禁在**所有屏之上**。锁定时清空全部 Tab 的栈——
    // 解锁后不该落回锁定前的页面。
    LaunchedEffect(state.locked) {
        if (state.locked) stacks.values.forEach { it.clear() }
        stacks[AppTab.PASSWORDS]?.add(Screen.List)
        stacks[AppTab.GENERATOR]?.add(Screen.Generator)
        stacks[AppTab.SECURITY]?.add(Screen.Security)
        stacks[AppTab.SETTINGS]?.add(Screen.Settings)
    }

    // T49 系统返回键：栈深为 1 时不接管，交回系统默认行为（退出应用）
    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }

    Scaffold(
        bottomBar = {
            // 锁定/未初始化时不显示底栏——那时只有解锁屏
            if (!state.locked && state.initialized) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { selectTab(tab) },
                            // 自绘 drawable 内部带 ?attr/colorControlNormal，
                            // 必须走 Icon（SrcIn 覆写为主题色）而不是 Image
                            icon = {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 内边距必须用上，否则内容会被底部导航栏压住（lint UnusedMaterial3ScaffoldPadding）
        Column(Modifier.padding(padding)) {
        if (state.locked || !state.initialized) {
            UnlockScreen(
                state = state,
                errorMessage = state.errorMessage,
                onInitialize = viewModel::initialize,
                onUnlock = viewModel::unlock,
                onDestroyVault = viewModel::destroyCorruptedVault,
                onBeginRecovery = viewModel::beginRecovery,
                onRecover = viewModel::recoverVault,
                onCancelRecovery = viewModel::cancelRecovery,
                onBiometricUnlock = biometricUnlockAction(viewModel),
            )
        } else when (top) {
            Screen.List -> EntryListScreen(
                state = state,
                categories = state.categories,
                errorMessage = state.errorMessage,
                onQueryChange = viewModel::search,
                onSelectCategory = viewModel::filterByCategory,
                onSelectFavorites = viewModel::filterByFavoritesOnly,
                onOpen = { backStack.add(Screen.Detail(it)) },
                onCreate = { backStack.add(Screen.Edit(null)) },
                onLock = viewModel::lock,
            )

            Screen.Generator -> GeneratorScreen(
                initialLength = state.defaultPasswordLength,
                options = state.generatorOptions,
                onOptionsChange = viewModel::setGeneratorOptions,
                onCopy = viewModel::copyGeneratedPassword,
            )

            Screen.Security -> SecurityCenterScreen(
                state = state,
                onScan = viewModel::startScan,
                onOpenEntry = { entryId ->
                    // 跨 Tab 跳转：切到密码 Tab 再压入详情
                    currentTab = AppTab.PASSWORDS
                    stacks.getValue(AppTab.PASSWORDS).add(Screen.Detail(entryId))
                },
            )

            Screen.About -> AboutScreen(
                versionName = versionName,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.SettingAutoLock -> AutoLockSettingScreen(
                current = state.autoLockDuration,
                onSelect = viewModel::setAutoLockDuration,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.SettingBiometric -> BiometricSettingScreen(
                enabled = state.biometricEnabled,
                onToggle = biometricToggleAction(viewModel),
                onRunDiagnostics = { BiometricDiagnostics.run(appContext).toText() },
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.SettingDefaultLength -> DefaultLengthSettingScreen(
                current = state.defaultPasswordLength,
                onChange = viewModel::setDefaultPasswordLength,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.SettingCategories -> CategorySettingScreen(
                categories = state.categories,
                onUpsert = viewModel::upsertCategory,
                onDelete = viewModel::deleteCategory,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.SettingChangePassword -> ChangePasswordScreen(
                errorMessage = state.errorMessage,
                successMessage = state.successMessage,
                onChange = { current, new, confirm ->
                    viewModel.submitMasterPasswordChange(current, new, confirm) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                },
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.Settings -> SettingsScreen(
                biometricEnabled = state.biometricEnabled,
                categories = state.categories,
                autoLockDuration = state.autoLockDuration,
                defaultPasswordLength = state.defaultPasswordLength,
                appVersionName = versionName,
                errorMessage = state.errorMessage,
                successMessage = state.successMessage,
                onOpenAutoLock = { backStack.add(Screen.SettingAutoLock) },
                onOpenBiometric = { backStack.add(Screen.SettingBiometric) },
                onOpenDefaultLength = { backStack.add(Screen.SettingDefaultLength) },
                onOpenCategories = { backStack.add(Screen.SettingCategories) },
                onOpenChangePassword = { backStack.add(Screen.SettingChangePassword) },
                onOpenAbout = { backStack.add(Screen.About) },
                onLock = viewModel::lock,
                onComingSoon = { message ->
                    snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                },
            )

            is Screen.Detail -> {
                val detailState = rememberDetailState(top.entryId, viewModel)
                EntryDetailScreen(
                    detail = detailState,
                    categories = state.categories,
                    revealed = state.revealed[top.entryId],
                    errorMessage = state.errorMessage,
                    onReveal = { viewModel.reveal(top.entryId) },
                    onHide = { viewModel.hide(top.entryId) },
                    onCopy = { viewModel.copyPassword(top.entryId) },
                    onCopyUsername = { viewModel.copyPlainText(detailState?.username.orEmpty()) },
                    onDelete = {
                        viewModel.delete(top.entryId)
                        backStack.removeAt(backStack.lastIndex)
                    },
                    onEdit = { backStack.add(Screen.Edit(top.entryId)) },
                    onToggleFavorite = { favorite -> viewModel.toggleFavorite(top.entryId, favorite) },
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                )
            }

            is Screen.Edit -> {
                val existing = rememberDetailState(top.entryId, viewModel)
                EntryEditScreen(
                    existing = existing,
                    categories = state.categories,
                    revealed = top.entryId?.let { state.revealed[it] },
                    errorMessage = state.errorMessage,
                    onGeneratePassword = viewModel::generatePasswordNow,
                    onCopyPassword = viewModel::copyPlainText,
                    onReveal = { top.entryId?.let(viewModel::reveal) },
                    onHide = { top.entryId?.let(viewModel::hide) },
                    onSave = { input -> viewModel.save(top.entryId, input) { backStack.removeAt(backStack.lastIndex) } },
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                )
            }
        }
        }
    }
}

/** 进入详情/编辑页即加载详情；明文不在此获取（经 reveal 单独请求）。新建（id 为 null）无需加载。 */
@Composable
private fun rememberDetailState(entryId: String?, viewModel: VaultViewModel): EntryDetail? {
    LaunchedEffect(entryId) { entryId?.let(viewModel::loadDetail) }
    return entryId?.let(viewModel::detailFor)
}

/**
 * 生物识别解锁的编排（v0-10）：**开启**需两段——数据层验主密码 → `BiometricPrompt`
 * 认证 → 数据层用已认证 cipher 包裹。关闭则只需验主密码。
 */
@Composable
private fun biometricToggleAction(viewModel: VaultViewModel): (Boolean, String) -> Unit {
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val dekStore = (LocalContext.current.applicationContext as? AppApplication)?.biometricDekStore
    val authenticator = remember(activity, dekStore) {
        if (activity != null && dekStore != null) BiometricAuthenticator(activity, dekStore) else null
    }

    return toggle@{ enable, masterPassword ->
        if (!enable) {
            viewModel.disableBiometric(masterPassword)
            return@toggle
        }
        if (authenticator == null) return@toggle
        scope.launch {
            val setup = viewModel.prepareBiometricEnable(masterPassword) ?: return@launch
            try {
                val cipher = authenticator.authenticateEncryptCipher()
                viewModel.completeBiometricEnable(cipher)
            } catch (e: BiometricAuthException) {
                viewModel.clearPendingBiometricEnable()
                viewModel.onBiometricAuthError(e.error)
            }
        }
    }
}

/** 生物识别解锁的一次完整编排（FR-2/V1-4）。 */
@Composable
private fun biometricUnlockAction(viewModel: VaultViewModel): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? FragmentActivity ?: return null
    val dekStore = (activity.application as? AppApplication)?.biometricDekStore ?: return null
    val authenticator = remember(activity, dekStore) { BiometricAuthenticator(activity, dekStore) }

    return {
        scope.launch {
            val challenge = viewModel.beginBiometricUnlock().getOrNull() ?: return@launch
            try {
                val cipher = authenticator.authenticateDecryptCipher(challenge.wrappedDek)
                viewModel.completeBiometricUnlock(challenge, cipher)
            } catch (e: BiometricAuthException) {
                viewModel.onBiometricAuthError(e.error)
            }
        }
    }
}

/** 诊断报告转可读文本（设置页弹窗直接展示）。 */
private fun BiometricDiagnostics.Report.toText(): String = buildString {
    appendLine("设备：$deviceInfo")
    appendLine("弱认证 WEAK：$weakResult")
    appendLine("强认证 STRONG：$strongResult")
    appendLine("Keystore 强认证密钥：$keystoreResult")
    appendLine()
    append(conclusion)
}
