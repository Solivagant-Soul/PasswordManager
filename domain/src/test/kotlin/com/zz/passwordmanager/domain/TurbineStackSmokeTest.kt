package com.zz.passwordmanager.domain

import app.cash.turbine.test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** T04 测试脚手架冒烟：Turbine + runTest 在 :domain 可用。 */
class TurbineStackSmokeTest {

    @Test
    fun `turbine consumes flow items`() = runTest {
        flow {
            emit(1)
            emit(2)
        }.test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            awaitComplete()
        }
    }
}
