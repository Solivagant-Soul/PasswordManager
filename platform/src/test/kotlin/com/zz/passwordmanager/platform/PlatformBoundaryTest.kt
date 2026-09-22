package com.zz.passwordmanager.platform

import com.zz.passwordmanager.domain.UseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模块边界冒烟：platform 层可以消费 domain 契约（platform → domain 方向）。
 * 反向依赖（domain → platform）由 Gradle 依赖图在编译期直接拒绝。
 */
class PlatformBoundaryTest {

    @Test
    fun `platform layer consumes domain contracts`() = runBlocking {
        val useCase = UseCase { seconds: Int -> seconds * 1000L }

        assertEquals(30_000L, useCase(30))
    }
}
