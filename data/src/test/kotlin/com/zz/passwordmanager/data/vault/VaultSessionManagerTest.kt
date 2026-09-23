package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** T12 验收：状态机/门禁/DEK 清零/自动锁定联动。 */
class VaultSessionManagerTest {

    private val manager = VaultSessionManager()
    private val dek = ByteArray(32) { 7 }

    @Test
    fun `starts locked`() = runTest {
        assertEquals(VaultState.Locked, manager.state.first())
    }

    @Test
    fun `begin enters unlocked with session`() = runTest {
        manager.begin(Session(dek.copyOf()))

        assertTrue(manager.state.first() is VaultState.Unlocked)
    }

    @Test
    fun `requireUnlocked throws when locked`() {
        assertThrows(VaultLockedException::class.java) { manager.requireUnlocked() }
    }

    @Test
    fun `requireUnlocked returns session when unlocked`() {
        val session = Session(dek.copyOf())
        manager.begin(session)

        assertEquals(session, manager.requireUnlocked())
    }

    @Test
    fun `lock clears dek bytes and returns to locked`() = runTest {
        val session = Session(dek.copyOf())
        manager.begin(session)

        manager.lock()

        assertEquals(VaultState.Locked, manager.state.first())
        assertTrue("DEK must be zeroed on lock", session.dek.contentEquals(ByteArray(32)))
    }

    @Test
    fun `background then foreground within threshold keeps unlocked`() = runTest {
        manager.begin(Session(dek.copyOf()))

        manager.onBackground(1_000L)
        manager.onForeground(1_000L + 10_000L, AutoLockDuration.M1)

        assertTrue(manager.state.first() is VaultState.Unlocked)
    }

    @Test
    fun `background then foreground past threshold locks`() = runTest {
        val session = Session(dek.copyOf())
        manager.begin(session)

        manager.onBackground(1_000L)
        manager.onForeground(1_000L + 60_000L, AutoLockDuration.M1)

        assertEquals(VaultState.Locked, manager.state.first())
        assertTrue("auto-lock must clear DEK", session.dek.contentEquals(ByteArray(32)))
    }

    @Test
    fun `background while locked is a no-op`() = runTest {
        manager.onBackground(1_000L)

        manager.onForeground(1_000L + 600_000L, AutoLockDuration.M1)

        assertEquals(VaultState.Locked, manager.state.first())
    }
}
