package com.zz.passwordmanager.core

/**
 * 统一的业务结果封装（技术方案 §5：仓库层尽量以 Result 风格返回）。
 *
 * Ok 携带成功值；Error 只携带面向用户的消息，不携带异常——
 * 避免把技术细节（异常栈/错误码）漏进领域层，日志脱敏（T29）在更外层处理。
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Error(val message: String) : AppResult<Nothing>

    /** 按分支消费：成功走 [onOk]，失败走 [onError]，返回统一的变换结果。 */
    fun <R> fold(onOk: (T) -> R, onError: (String) -> R): R = when (this) {
        is Ok -> onOk(value)
        is Error -> onError(message)
    }
}
