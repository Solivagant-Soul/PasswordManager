package com.zz.passwordmanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.zz.passwordmanager.AppApplication
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zz.passwordmanager.domain.entries.EntryDetail
import kotlinx.coroutines.launch

/** 手写轻量导航（M2 决策）：sealed Screen + 回退栈；3 屏规模不引 navigation-compose。 */
sealed interface Screen {
    data object List : Screen
    data class Detail(val entryId: String) : Screen
    data class Edit(val entryId: String?) : Screen
    data object Settings : Screen
}

@Composable
fun AppNav(viewModel: VaultViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val backStack: SnapshotStateList<Screen> = remember { mutableStateListOf(Screen.List) }
    val top = backStack.last()

    // FR-8/BR-7：锁定门禁必须在**所有屏之上**。若只在 List 分支判断，
    // 用户在详情/编辑页切后台超时后回来，仍会停在该页（且数据已清空 → 空白页、退出路径诡异）。
    // 锁定时连回退栈一并清空，避免解锁后落回旧页面。
    LaunchedEffect(state.locked) {
        if (state.locked && backStack.size > 1) backStack.removeAll { it != Screen.List }
    }

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
        return
    }

    // 详情数据：按栈顶 id 从仓库取（M2 简化：由 VM 的列表缓存 + get 组合）
    when (top) {
        Screen.List -> {
            EntryListScreen(
                state = state,
                errorMessage = state.errorMessage,
                onQueryChange = viewModel::search,
                onOpen = { backStack.add(Screen.Detail(it)) },
                onCreate = { backStack.add(Screen.Edit(null)) },
                onSettings = { backStack.add(Screen.Settings) },
                onLock = viewModel::lock,
            )
        }

        Screen.Settings -> {
            SettingsScreen(
                errorMessage = state.errorMessage,
                successMessage = state.successMessage,
                biometricEnabled = state.biometricEnabled,
                onSetBiometric = viewModel::setBiometricEnabled,
                onChangeMasterPassword = { current, new, confirm ->
                    viewModel.submitMasterPasswordChange(current, new, confirm) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                },
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )
        }

        is Screen.Detail -> {
            val detailState = rememberDetailState(top.entryId, viewModel)
            EntryDetailScreen(
                detail = detailState,
                revealed = state.revealed[top.entryId],
                errorMessage = state.errorMessage,
                onReveal = { viewModel.reveal(top.entryId) },
                onHide = { viewModel.hide(top.entryId) },
                onCopy = { viewModel.copyPassword(top.entryId) },
                onDelete = {
                    viewModel.delete(top.entryId)
                    backStack.removeAt(backStack.lastIndex)
                },
                onEdit = { backStack.add(Screen.Edit(top.entryId)) },
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )
        }

        is Screen.Edit -> {
            val existing = rememberDetailState(top.entryId, viewModel)
            EntryEditScreen(
                existing = existing,
                errorMessage = state.errorMessage,
                onSave = { input -> viewModel.save(top.entryId, input) { backStack.removeAt(backStack.lastIndex) } },
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )
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
 * 生物识别解锁的编排（FR-2/V1-4）：用例第一段资格判定 → `BiometricPrompt` 认证
 * → 用例第二段用已认证 cipher 建会话。
 *
 * 认证本身需要 Activity 上下文（`BiometricPrompt` 硬要求 FragmentActivity），
 * 只能在组合层驱动；用例只管数据与状态，故这里是唯一的编排点。
 */
@Composable
private fun biometricUnlockAction(viewModel: VaultViewModel): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? FragmentActivity ?: return null
    val dekStore = (activity.application as? AppApplication)?.biometricDekStore
    if (dekStore == null) return null
    val authenticator = remember(activity, dekStore) { BiometricAuthenticator(activity, dekStore) }

    return {
        scope.launch {
            val challenge = viewModel.beginBiometricUnlock().getOrNull() ?: return@launch
            try {
                val cipher = authenticator.authenticateDecryptCipher()
                viewModel.completeBiometricUnlock(challenge, cipher)
            } catch (e: BiometricAuthException) {
                viewModel.onBiometricAuthError(e.error)
            }
        }
    }
}