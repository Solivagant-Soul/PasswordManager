package com.zz.passwordmanager.domain.autolock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T08 验收：各档位生效；边界含等号；时钟回拨宁严勿松。 */
class AutoLockPolicyTest {

    private val backgroundedAt = 1_000_000L

    @Test
    fun `m1 locks after sixty seconds`() {
        assertTrue(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt + 60_000, AutoLockDuration.M1))
    }

    @Test
    fun `m1 does not lock before sixty seconds`() {
        assertFalse(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt + 59_999, AutoLockDuration.M1))
    }

    @Test
    fun `boundary equality locks (inclusive)`() {
        assertTrue(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt + 30_000, AutoLockDuration.S30))
        assertFalse(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt + 29_999, AutoLockDuration.S30))
    }

    @Test
    fun `immediate locks on any elapsed`() {
        assertTrue(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt, AutoLockDuration.IMMEDIATE))
    }

    @Test
    fun `m5 thirty seconds does not lock`() {
        assertFalse(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt + 30_000, AutoLockDuration.M5))
    }

    @Test
    fun `clock rollback locks (fail closed)`() {
        assertTrue(AutoLockPolicy.shouldLock(backgroundedAt, backgroundedAt - 1_000, AutoLockDuration.M1))
    }
}
