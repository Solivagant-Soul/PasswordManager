package com.zz.passwordmanager.domain.password

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T19/feature 04 验收：按规则生成、未选字符集、长度越界、4/32 边界、排除易混淆字符。
 * 随机源注入确定性序列，断言可重复。
 */
class PasswordGeneratorTest {

    /** 依次返回固定下标，便于断言「取自字符池」。 */
    private class FixedRandom(private val indices: List<Int>) : SecureRandom() {
        private var cursor = 0
        override fun nextInt(bound: Int): Int {
            val value = indices[cursor % indices.size]
            cursor++
            return value % bound
        }
    }

    @Test
    fun `generates password of requested length from selected sets`() {
        val rules = PasswordGenerator.Rules(
            length = 12,
            sets = setOf(PasswordGenerator.CharSet.DIGIT),
            excludeAmbiguous = false,
        )

        val result = PasswordGenerator.generate(rules, FixedRandom(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1)))

        val password = result.getOrThrow().concatToString()
        assertEquals(12, password.length)
        assertTrue(password.all { it.isDigit() })
    }

    @Test
    fun `fails when no character set is selected`() {
        val rules = PasswordGenerator.Rules(length = 8, sets = emptySet())

        val error = (PasswordGenerator.generate(rules).exceptionOrNull() as PasswordGenerator.GeneratorException)

        assertEquals(PasswordGenerator.Reason.NO_CHARSET, error.reason)
    }

    @Test
    fun `fails when length is below minimum`() {
        val rules = PasswordGenerator.Rules(length = PasswordGenerator.LENGTH_MIN - 1)

        val error = (PasswordGenerator.generate(rules).exceptionOrNull() as PasswordGenerator.GeneratorException)

        assertEquals(PasswordGenerator.Reason.LENGTH_OUT_OF_RANGE, error.reason)
    }

    @Test
    fun `fails when length is above maximum`() {
        val rules = PasswordGenerator.Rules(length = PasswordGenerator.LENGTH_MAX + 1)

        val error = (PasswordGenerator.generate(rules).exceptionOrNull() as PasswordGenerator.GeneratorException)

        assertEquals(PasswordGenerator.Reason.LENGTH_OUT_OF_RANGE, error.reason)
    }

    @Test
    fun `accepts minimum boundary length`() {
        val rules = PasswordGenerator.Rules(length = PasswordGenerator.LENGTH_MIN)

        assertEquals(PasswordGenerator.LENGTH_MIN, PasswordGenerator.generate(rules).getOrThrow().size)
    }

    @Test
    fun `accepts maximum boundary length`() {
        val rules = PasswordGenerator.Rules(length = PasswordGenerator.LENGTH_MAX)

        assertEquals(PasswordGenerator.LENGTH_MAX, PasswordGenerator.generate(rules).getOrThrow().size)
    }

    @Test
    fun `excludes ambiguous characters when asked`() {
        val rules = PasswordGenerator.Rules(length = 32, excludeAmbiguous = true)

        val password = PasswordGenerator.generate(rules).getOrThrow().concatToString()

        assertTrue(
            "不应包含易混淆字符，实际：$password",
            password.none { it in PasswordGenerator.AMBIGUOUS_CHARS },
        )
    }

    @Test
    fun `keeps ambiguous characters when not excluded`() {
        // 固定随机源恒取下标 0，而数字池首字符是 '0'——关掉排除后必然取到易混淆字符。
        val rules = PasswordGenerator.Rules(
            length = PasswordGenerator.LENGTH_MIN,
            sets = setOf(PasswordGenerator.CharSet.DIGIT),
            excludeAmbiguous = false,
        )

        val password = PasswordGenerator.generate(rules, FixedRandom(listOf(0))).getOrThrow().concatToString()

        assertTrue("应保留易混淆字符，实际：$password", password.any { it in PasswordGenerator.AMBIGUOUS_CHARS })
    }

    @Test
    fun `default rules use every set and stay unambiguous`() {
        val password = PasswordGenerator.generate(PasswordGenerator.Rules()).getOrThrow().concatToString()

        assertEquals(16, password.length)
        assertTrue(password.none { it in PasswordGenerator.AMBIGUOUS_CHARS })
    }

    /** v2.1：长度上界由 64 收窄到 32（spec D-长度），边界两侧都要钉住。 */
    @Test
    fun `upper boundary 32 is accepted`() {
        val rules = PasswordGenerator.Rules(length = 32, excludeAmbiguous = true)
        val result = PasswordGenerator.generate(rules)
        assertTrue(result.isSuccess)
        assertEquals(32, result.getOrThrow().size)
    }

    @Test
    fun `length above 32 is rejected`() {
        val rules = PasswordGenerator.Rules(length = 33, excludeAmbiguous = true)
        val result = PasswordGenerator.generate(rules)
        assertTrue(result.isFailure)
        assertEquals(
            PasswordGenerator.Reason.LENGTH_OUT_OF_RANGE,
            (result.exceptionOrNull() as PasswordGenerator.GeneratorException).reason,
        )
    }
}
