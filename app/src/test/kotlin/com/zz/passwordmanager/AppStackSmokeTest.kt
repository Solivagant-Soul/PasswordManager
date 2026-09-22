package com.zz.passwordmanager

import com.zz.passwordmanager.domain.UseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** T04 测试脚手架冒烟：MockK 可 mock domain 契约（app 层典型用法：ViewModel 测试预备）。 */
class AppStackSmokeTest {

    @Test
    fun `mockk mocks domain use case`() = runTest {
        val useCase = mockk<UseCase<String, Int>>()
        coEvery { useCase(any()) } returns 42

        assertEquals(42, useCase("t04"))

        coVerify { useCase("t04") }
    }
}
