package com.zz.passwordmanager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T01 占位单测：验证测试基建（JUnit 可运行）。
 * 真正的领域单测自 T05（KeyManager）开始。
 */
class SmokeTest {

    @Test
    fun `test infrastructure is operational`() {
        assertEquals(4, 2 + 2)
    }
}
