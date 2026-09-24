package com.zz.passwordmanager.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import javax.crypto.Cipher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 生物识别交互失败，携带可直接降级到主密码的 [AuthError]。 */
class BiometricAuthException(val error: AuthError) : Exception(error.toString())

/**
 * 生物识别认证桥（FR-2 / V1-4）。
 *
 * 放在 `:app` 是因为它依赖 `BiometricPrompt` + `FragmentActivity` 这类 UI 上下文，
 * `:data`/`:platform` 都不持有。
 *
 * **本类存在的全部理由是 auth-per-use 的时序（G-E）**：
 * 1. `createDecryptCipher()` 必须在**任何用户认证之前**调用（Keystore 密钥认证有效期为
 *    0 秒 / validity -1），所以 cipher 在方法入口创建，而不是等 authenticate 之后；
 * 2. 认证成功后取 `result.cryptoObject!!.cipher`——那是**框架返回的、已认证的那把** cipher，
 *    绝不复用第 1 步那把。密钥操作与本次认证绑定，无法在认证后另行使用。
 */
class BiometricAuthenticator(
    private val activity: FragmentActivity,
    private val dekStore: BiometricDekStore,
) {

    /**
     * 弹出生物识别提示并等待结果；成功返回**已认证**的解密 cipher。
     *
     * 取消/失败不视为错误处理上的异常，一律通过 [BiometricAuthException] 携带
     * 可静默降级的 [AuthError] 交由调用方决定是否提示（取消不应被当成错误提示用户）。
     */
    suspend fun authenticateDecryptCipher(): Cipher =
        authenticate(title = "解锁密码库", subtitle = "使用指纹或面容验证") { dekStore.createDecryptCipher() }

    /**
     * 开启快捷解锁时用：认证并返回**已认证的加密 cipher**（v0.10）。
     *
     * 之所以开启也要认证：Keystore 对 auth-required 密钥的加密与解密一视同仁，
     * 包裹 DEK 这一步同样需要认证。顺带的好处是——开启时就验证了生物识别真的能用，
     * 避免「开了之后到解锁时才发现不可用」。
     */
    suspend fun authenticateEncryptCipher(): Cipher =
        authenticate(title = "启用生物识别解锁", subtitle = "请先验证一次指纹或面容") {
            dekStore.createEncryptCipher()
        }

    private suspend fun authenticate(
        title: String,
        subtitle: String,
        createCipher: () -> Cipher,
    ): Cipher = suspendCancellableCoroutine { continuation ->
        // ① 认证前建 cipher（auth-per-use 的时序要求）
        val cipher = try {
            createCipher()
        } catch (e: Exception) {
            continuation.resumeWithException(
                BiometricAuthException(AuthError.BiometricSetupFailed("${e.javaClass.simpleName}: ${e.message}")),
            )
            return@suspendCancellableCoroutine
        }

        lateinit var prompt: BiometricPrompt
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // ② 用框架返回的已认证 cipher
                val authenticated = result.cryptoObject?.cipher
                if (authenticated == null) {
                    continuation.resumeWithException(
                        BiometricAuthException(AuthError.BiometricSetupFailed("认证成功但未返回加密对象")),
                    )
                } else {
                    continuation.resume(authenticated)
                }
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                continuation.resumeWithException(BiometricAuthException(mapError(code)))
            }

            // onAuthenticationFailed 是「单次识别未通过」（按错手指），系统会继续弹提示，
            // 不在此 resume——否则用户按错一次就被踢出流程。
        }

        prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("用主密码")
            .build()

        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun mapError(code: Int): AuthError = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        -> AuthError.BiometricCancelled

        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> AuthError.BiometricLockedOut

        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        -> AuthError.BiometricUnavailable(availabilityOrUnknown())

        else -> AuthError.BiometricCancelled
    }

    private fun availabilityOrUnknown() = com.zz.passwordmanager.domain.biometric.BiometricGate.Availability.NOT_ENROLLED
}
