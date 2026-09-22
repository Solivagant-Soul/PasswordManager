package com.zz.passwordmanager.data

import com.zz.passwordmanager.domain.UseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模块边界冒烟：data 层可以消费 domain 契约（data → domain 方向）。
 * 反向依赖（domain → data）由 Gradle 依赖图在编译期直接拒绝。
 */
class DataBoundaryTest {

    @Test
    fun `data layer consumes domain contracts`() = runBlocking {
        val useCase = UseCase { id: String -> "entry:$id" }

        assertEquals("entry:t02", useCase("t02"))
    }
}
