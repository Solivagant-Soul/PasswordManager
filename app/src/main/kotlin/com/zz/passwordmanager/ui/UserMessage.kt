package com.zz.passwordmanager.ui

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
    is AuthError.BiometricUnavailable -> when (error.availability) {
        BiometricGate.Availability.AVAILABLE -> "生物识别不可用，请用主密码解锁"
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

/** 把内部校验串归并成人话；只暴露「哪一项有问题」，不暴露内部规则常量。 */
private fun invalidMessage(issues: List<String>): String = when {
    issues.isEmpty() -> "输入不符合要求"
    issues.any { it.contains("mismatch") } -> "两次输入不一致"
    issues.any { it.contains("securityQuestions") } -> "密保问题或答案不符合要求（需 3 道，答案不超过 60 字）"
    issues.any { it.contains("title") } -> "标题必填且不超过 100 字"
    issues.any { it.contains("password") || it.contains("newPassword") } ->
        "密码至少 8 位，且需包含大写、小写、数字、符号中的两类"
    issues.any { it.contains("url") } -> "网址须以 http:// 或 https:// 开头"
    issues.any { it.contains("tags") } -> "标签最多 20 个"
    else -> "输入不符合要求"
}
