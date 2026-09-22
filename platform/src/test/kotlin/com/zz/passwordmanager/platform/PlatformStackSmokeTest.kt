package com.zz.passwordmanager.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** T04 测试脚手架冒烟：coroutines-test（runTest）在 :platform 可用。 */
class PlatformStackSmokeTest {

    @Test
    fun `coroutines test infra is operational`() = runTest {
        val deferred = async { 42 }

        assertEquals(42, deferred.await())
    }
}
