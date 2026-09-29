package com.zz.passwordmanager.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.zz.passwordmanager.data.vault.AutofillFill
import com.zz.passwordmanager.data.vault.AutofillSessionError
import com.zz.passwordmanager.data.vault.AutofillSessionException
import com.zz.passwordmanager.data.vault.AutofillVaultSession
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.domain.autofill.AutofillCandidate
import com.zz.passwordmanager.domain.autofill.AutofillTarget
import com.zz.passwordmanager.ui.components.TopBar
import com.zz.passwordmanager.ui.theme.PasswordManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 自动填充的认证页（FR-6，v1.2.0；技术方案 §9.2 的落地）。
 *
 * ## 生命周期就是会话边界
 *
 * DEK 只在这个 Activity 活着的时候存在。三条清零路径，缺一不可：
 *
 * 1. **成功回填** —— [pick] 的 `finally`：无论构造 `FillResponse` 成功、抛异常，
 *    还是协程在途中被取消（此时 `CancellationException` 也会照常走过 `finally`）；
 * 2. **认证/取候选失败** —— [submit] 的 `finally`：`handedOff` 为 false 即清，
 *    失败绝不留在解锁态；
 * 3. **用户放弃** —— [onDestroy]：返回键、流程取消、任务被切走。
 *    配置变更（旋转）时**不清**，否则转个屏就要重输主密码；这一豁免由
 *    [AutofillVaultSession] 的 TTL 兜底。
 *
 * 解锁走 [com.zz.passwordmanager.data.vault.UnlockVault]，即真正跑一遍 Argon2 派生 +
 * 解包；本 Activity 与 [VaultAutofillService] 都不缓存 DEK，也不碰 UI 那条长会话
 * ——见 [AutofillVaultSession] 的 KDoc。
 *
 * 本 Activity **不接入 App 的导航体系**：它的产物是交给系统的 `FillResponse`，
 * 不是一屏可回退的界面。
 */
@AndroidEntryPoint
class AutofillAuthActivity : ComponentActivity() {

    @Inject
    lateinit var autofillSession: AutofillVaultSession

    private val ui = MutableStateFlow<AuthUiState>(AuthUiState.Prompt)
    private var form: ParsedForm? = null

    @Suppress("DEPRECATION") // minSdk 26：getParcelableExtra(String) 是本档唯一可用的重载
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = runCatching {
            ParsedForm(
                usernameId = intent.getParcelableExtra<AutofillId>(EXTRA_USERNAME_ID),
                passwordId = intent.getParcelableExtra<AutofillId>(EXTRA_PASSWORD_ID) ?: return@runCatching null,
                target = AutofillTarget(
                    packageName = intent.getStringExtra(EXTRA_PACKAGE),
                    domain = intent.getStringExtra(EXTRA_DOMAIN),
                ),
            )
        }.getOrNull()

        // 拿不到目标字段就没法回填，直接取消，而不是停在半截流程里等用户干瞪眼。
        if (parsed == null) {
            cancel()
            return
        }
        form = parsed

        // 返回键/下拉手势：显式回 RESULT_CANCELED，让系统按「用户放弃」处理，
        // 而不是把一个空结果当成认证通过。
        onBackPressedDispatcher.addCallback(this) { cancel() }

        setContent {
            PasswordManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthForm(
                        state = ui.collectAsState().value,
                        target = parsed.target,
                        onSubmit = ::submit,
                        onPick = ::pick,
                        onCancel = ::cancel,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) autofillSession.lock()
        super.onDestroy()
    }

    /** 输主密码 → 开启一次会话 → 取候选。整体是**一次原子操作**，故一个 `finally` 兜住。 */
    private fun submit(password: String) {
        ui.value = AuthUiState.Loading
        lifecycleScope.launch {
            var handedOff = false
            try {
                autofillSession.unlock(password.toCharArray()).getOrThrow()
                val target = form?.target ?: throw AutofillSessionException(AutofillSessionError.LOCKED)
                ui.value = AuthUiState.Candidates(autofillSession.candidates(target))
                handedOff = true // 会话移交候选页，之后由 pick / onDestroy 负责清
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) ui.value = AuthUiState.Error(describe(t))
            } finally {
                if (!handedOff) autofillSession.lock()
            }
        }
    }

    /** 用户点定一条 → 取明文 → 回填 → 交回系统。无论结果如何都清会话。 */
    private fun pick(candidate: AutofillCandidate) {
        val parsed = form ?: return cancel()
        lifecycleScope.launch {
            try {
                val fill = autofillSession.fill(candidate.entryId).getOrThrow()
                val response = buildResponse(parsed, candidate, fill)
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
                )
                finish()
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) ui.value = AuthUiState.Error(describe(t))
            } finally {
                autofillSession.lock()
            }
        }
    }

    /**
     * 组装回填用的 `FillResponse`。
     *
     * 这里把 `CharArray` 摊成 `String` 是框架 API 的硬要求（`AutofillValue` 只吃
     * CharSequence），无法避免；因此**调用方不得把 response 存进任何字段**——
     * 它随 `setResult` 交给系统，本对象随即被回收。
     */
    private fun buildResponse(
        parsed: ParsedForm,
        candidate: AutofillCandidate,
        fill: AutofillFill,
    ): FillResponse {
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
            setTextViewText(android.R.id.text1, candidate.title)
            setTextViewText(android.R.id.text2, candidate.username)
        }
        val builder = Dataset.Builder()
            .setValue(parsed.passwordId, AutofillValue.forText(fill.password.concatToString()), presentation)
        parsed.usernameId?.let { builder.setValue(it, AutofillValue.forText(fill.username), presentation) }
        return FillResponse.Builder().addDataset(builder.build()).build()
    }

    private fun cancel() {
        autofillSession.lock()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun describe(t: Throwable): String = when (t) {
        is AuthException -> when (val e = t.error) {
            is AuthError.WrongPassword -> "主密码错误，还可尝试 ${e.attemptsRemaining} 次"
            is AuthError.LockedUntil -> "尝试次数过多，请稍后再试"
            else -> "解锁失败，请重试"
        }
        is AutofillSessionException -> when (t.error) {
            AutofillSessionError.LOCKED -> "会话已失效，请重新解锁"
            AutofillSessionError.OUT_OF_SCOPE -> "该条目不在本次候选中，请重新解锁"
            AutofillSessionError.NOT_FOUND -> "条目已不存在，请重新解锁"
        }
        else -> "解锁失败，请重试"
    }

    companion object {
        private const val EXTRA_USERNAME_ID = "com.zz.passwordmanager.autofill.USERNAME_ID"
        private const val EXTRA_PASSWORD_ID = "com.zz.passwordmanager.autofill.PASSWORD_ID"
        private const val EXTRA_PACKAGE = "com.zz.passwordmanager.autofill.PACKAGE"
        private const val EXTRA_DOMAIN = "com.zz.passwordmanager.autofill.DOMAIN"

        fun newIntent(context: Context, form: ParsedForm): Intent =
            Intent(context, AutofillAuthActivity::class.java)
                .putExtra(EXTRA_USERNAME_ID, form.usernameId)
                .putExtra(EXTRA_PASSWORD_ID, form.passwordId)
                .putExtra(EXTRA_PACKAGE, form.target.packageName)
                .putExtra(EXTRA_DOMAIN, form.target.domain)
    }
}

/** 认证页的状态机。`Loading` 显式存在，避免 Argon2 跑的那一两秒里按钮可重复点。 */
internal sealed interface AuthUiState {
    data object Prompt : AuthUiState
    data object Loading : AuthUiState
    data class Candidates(val items: List<AutofillCandidate>) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

@Composable
private fun AuthForm(
    state: AuthUiState,
    target: AutofillTarget,
    onSubmit: (String) -> Unit,
    onPick: (AutofillCandidate) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TopBar(title = "自动填充", onBack = onCancel)
        Text(
            text = target.domain ?: target.packageName ?: "未识别来源",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        when (state) {
            AuthUiState.Prompt, AuthUiState.Loading -> PasswordPrompt(
                password = password,
                busy = state is AuthUiState.Loading,
                onPasswordChange = { password = it },
                onSubmit = { onSubmit(password) },
            )

            is AuthUiState.Error -> PasswordPrompt(
                password = password,
                busy = false,
                error = state.message,
                onPasswordChange = { password = it },
                onSubmit = { onSubmit(password) },
            )

            is AuthUiState.Candidates -> if (state.items.isEmpty()) {
                // S39：有目标、无匹配 → 明说，不填。
                Text("没有匹配条目，请改用手动复制。", style = MaterialTheme.typography.bodyLarge)
            } else {
                CandidateList(state.items, onPick)
            }
        }
    }
}

@Composable
private fun PasswordPrompt(
    password: String,
    busy: Boolean,
    error: String? = null,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("主密码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // 刻意**不**给这一栏打自动填充 hint：主密码框一旦被任何 AutofillService
        // 认作目标，那条凭据就会落进（可能是第三方的）候选列表里。
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onSubmit,
        enabled = !busy && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "解锁中…" else "解锁并选择条目") }
    Spacer(Modifier.height(8.dp))
    Text(
        "本次解锁只服务于这一次填充，用完立即失效。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** S41：多条候选时给列表让用户挑，绝不自动填第一条。 */
@Composable
private fun CandidateList(items: List<AutofillCandidate>, onPick: (AutofillCandidate) -> Unit) {
    Text("选择要填充的条目", style = MaterialTheme.typography.bodyMedium)
    LazyColumn {
        items(items, key = { it.entryId }) { candidate ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(candidate) }
                    .padding(vertical = 12.dp),
            ) {
                Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    candidate.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
