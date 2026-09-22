package com.zz.passwordmanager.domain

/**
 * 用例的统一形态：单一输入、单一输出、可挂起。
 *
 * 所有领域用例实现此接口，ViewModel 只依赖该抽象（MVVM + UDF，方案 §2），
 * 使业务规则可以在纯 JVM 环境下测试。
 */
fun interface UseCase<I, O> {
    suspend operator fun invoke(input: I): O
}
