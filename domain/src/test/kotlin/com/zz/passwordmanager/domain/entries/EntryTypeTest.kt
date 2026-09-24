package com.zz.passwordmanager.domain.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T32/FR-15：条目类型的副标题渲染。
 * 重点是银行卡/证件**必须掩码**——不加类型时，卡号会原样出现在列表页。
 */
class EntryTypeTest {

    @Test
    fun `account shows username as is`() {
        assertEquals("user@example.com", EntryType.ACCOUNT.subtitle("user@example.com"))
    }

    @Test
    fun `wifi shows ssid as is`() {
        assertEquals("TP-LINK_5G", EntryType.WIFI.subtitle("TP-LINK_5G"))
    }

    @Test
    fun `bank card masks keeping first and last four`() {
        assertEquals("6225 **** 9012", EntryType.BANK_CARD.subtitle("6225123456789012"))
    }

    @Test
    fun `document masks keeping first and last four`() {
        assertEquals("1101 **** 1234", EntryType.DOCUMENT.subtitle("110101199001011234"))
    }

    @Test
    fun `short values are fully masked`() {
        assertEquals("****", EntryType.BANK_CARD.subtitle("1234"))
        assertEquals("", EntryType.BANK_CARD.subtitle(""))
    }

    @Test
    fun `medium values degrade to first char only`() {
        // 长度不足以「前 4 + 星号 + 后 4」，退化为只露首位
        assertEquals("6*******", EntryType.BANK_CARD.subtitle("62251234"))
    }

    @Test
    fun `masking never reveals the middle`() {
        val masked = EntryType.BANK_CARD.subtitle("6225123456789012")
        assertFalse("掩码结果不得含中间 8 位", masked.contains("12345678"))
    }

    @Test
    fun `unknown type falls back to account`() {
        assertEquals(EntryType.ACCOUNT, EntryType.from("NOT_A_TYPE"))
        assertEquals(EntryType.ACCOUNT, EntryType.from(null))
        assertEquals(EntryType.ACCOUNT, EntryType.from(""))
    }

    @Test
    fun `known type parses`() {
        assertEquals(EntryType.BANK_CARD, EntryType.from("BANK_CARD"))
        assertEquals(EntryType.WIFI, EntryType.from("WIFI"))
    }

    @Test
    fun `card types report needing masking`() {
        assertTrue(EntryType.BANK_CARD.needsMasking)
        assertTrue(EntryType.DOCUMENT.needsMasking)
        assertFalse(EntryType.ACCOUNT.needsMasking)
        assertFalse(EntryType.WIFI.needsMasking)
    }
}
