package com.zz.passwordmanager.data.header

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderMacTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private val data = "vault-header-payload".encodeToByteArray()

    @Test
    fun `mac is 32 bytes (sha256)`() {
        assertEquals(32, HeaderMac(key).compute(data).size)
    }

    @Test
    fun `verify accepts intact data`() {
        val mac = HeaderMac(key)

        assertTrue(mac.verify(data, mac.compute(data)))
    }

    @Test
    fun `verify rejects tampered data`() {
        val mac = HeaderMac(key)
        val expected = mac.compute(data)
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertFalse(mac.verify(tampered, expected))
    }

    @Test
    fun `verify rejects wrong key`() {
        val expected = HeaderMac(key).compute(data)

        assertFalse(HeaderMac(otherKey).verify(data, expected))
    }
}
