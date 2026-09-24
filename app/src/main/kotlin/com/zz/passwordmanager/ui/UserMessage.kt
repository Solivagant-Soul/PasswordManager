package com.zz.passwordmanager.ui

import com.zz.passwordmanager.data.entries.CategoryException
import com.zz.passwordmanager.data.vault.AuthError
import com.zz.passwordmanager.data.vault.AuthException
import com.zz.passwordmanager.data.vault.InitializationError
import com.zz.passwordmanager.data.vault.InitializationException
import com.zz.passwordmanager.domain.biometric.BiometricGate
import com.zz.passwordmanager.domain.entries.EntryRepositoryException

/**
 * 异常 → 面向用户的中文文案。
 *
 * 存在的理由：原先各调用点直接 `errorMessage = e.message`，会把内部串漏给用户——
 * 例如 `Issue(field="securityQuestions", message="expected 3 questions")` 的
 * `message.toString()` 显示出来既看不懂也无从行动。
 * 与 `core/AppResult`「Error 只携带面向用户的消息」的约定一致。
 */
internal fun userMessage(e: Throwable): String = when (e) {
    is AuthException -> authMessage(e.error)
    is InitializationException -> when (val error = e.error) {
        InitializationError.AlreadyInitialized -> "密码库已存在，请直接解锁"
        is InitializationError.Invalid -> invalidMessage(error.issues.map { it.message })
    }
    is CategoryException -> e.issues.firstOrNull()?.message ?: "分类名不符合要求"
    is EntryRepositoryException -> when (e.reason) {
        EntryRepositoryException.Reason.LIMIT_REACHED -> "条目已达上限（5000 条），请先删除一些"
        EntryRepositoryException.Reason.NOT_FOUND -> "条目不存在或已删除"
        EntryRepositoryException.Reason.INVALID -> invalidMessage(e.issues.map { it.message })
    }
    else -> "操作失败，请重试"
}

private fun authMessage(error: AuthError): String = when (error) {
    AuthError.VaultMissing -> "尚未创建密码库"
    is AuthError.LockedUntil -> lockMessage(error.remainingMs)
    is AuthError.WrongPassword -> if (error.attemptsRemaining > 0) {
        "主密码错误，还可尝试 ${error.attemptsRemaining} 次"
    } else {
        "主密码错误"
    }
    is AuthError.WrongAnswers -> if (error.attemptsRemaining > 0) {
        "密保答案错误，还可尝试 ${error.attemptsRemaining} 次"
    } else {
        "密保答案错误"
    }
    is AuthError.Invalid -> invalidMessage(error.issues.map { it.message })
    AuthError.BiometricNotEnabled -> "生物识别解锁未开启"
    // 取消/单次识别未通过由 VM 静默处理，不会走到这里；留兜底文案
    AuthError.BiometricCancelled -> "已取消生物识别，请用主密码解锁"
    AuthError.BiometricLockedOut -> "生物识别已锁定，请用主密码解锁"
    AuthError.BiometricKeyInvalidated -> "生物识别已失效，请用主密码解锁后在设置中重新开启"
    is AuthError.BiometricSetupFailed -> "开启生物识别失败：${error.reason}"
    // 硬件本身可用却走到这里 = 状态不一致，如实说明而不是谎称「未录入」
    is AuthError.BiometricUnavailable -> when (error.availability) {
        BiometricGate.Availability.AVAILABLE -> "生物识别状态异常，请用主密码解锁"
        BiometricGate.Availability.NO_HARDWARE -> "本机没有指纹/面容硬件，请用主密码解锁"
        BiometricGate.Availability.NOT_ENROLLED -> "尚未录入指纹/面容，请用主密码解锁"
    }
}

private fun lockMessage(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "尝试过多，请 $minutes 分 $seconds 秒后重试" else "尝试过多，请 $seconds 秒后重试"
}

/**
 * 把内部校验串归并成人话；只暴露「哪一项有问题」，不暴露内部规则常量。
 *
 * 匹配一律**转小写后**比较：字段名大小写不统一（`masterPassword` / `newPassword`
 * 都是驼峰），直接 `contains("password")` 会漏掉主密码类问题，一律落到兜底文案。
 */
private fun invalidMessage(issues: List<String>): String {
    fun has(keyword: String) = issues.any { it.lowercase().contains(keyword) }
    return when {
        issues.isEmpty() -> "输入不符合要求"
        has("mismatch") -> "两次输入不一致"
        has("securityquestions") -> "密保问题或答案不符合要求（需 3 道，答案不超过 60 字）"
        has("title") -> "标题必填且不超过 100 字"
        has("password") -> "密码至少 8 位，且需包含大写、小写、数字、符号中的两类"
        has("url") -> "网址须以 http:// 或 https:// 开头"
        has("tags") -> "标签最多 20 个"
        else -> "输入不符合要求"
    }
}

/** 分类相关错误的文案（与条目错误分开，便于 UI 定位到具体表单）。 */
internal fun categoryMessage(e: Throwable): String = when (e) {
    is CategoryException -> e.issues.firstOrNull()?.message ?: "分类名不符合要求"
    else -> userMessage(e)
}
