package com.zz.passwordmanager.domain.password

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1/D4：生成器选项是全局偏好（编辑页与生成器页共用），此处钉住映射与兜底。 */
class GeneratorOptionsTest {

    @Test
    fun `defaults enable all four char sets and exclude ambiguous`() {
        val options = GeneratorOptions()
        assertEquals(PasswordGenerator.CharSet.entries.toSet(), options.charSets())
        assertTrue(options.excludeAmbiguous)
    }

    /** 五个开关全关时 charSets 为空——调用方必须据此阻止生成，而不是让生成器抛。 */
    @Test
    fun `all switches off yields an empty char set`() {
        val options = GeneratorOptions(
            useUpper = false, useLower = false, useDigit = false, useSymbol = false,
        )
        assertTrue(options.charSets().isEmpty())
    }

    @Test
    fun `char set mapping honours each switch independently`() {
        val options = GeneratorOptions(
            useUpper = true, useLower = false, useDigit = true, useSymbol = false,
        )
        assertEquals(
            setOf(PasswordGenerator.CharSet.UPPER, PasswordGenerator.CharSet.DIGIT),
            options.charSets(),
        )
    }

    @Test
    fun `rules carry the options through`() {
        val options = GeneratorOptions(
            useUpper = true, useLower = false, useDigit = true, useSymbol = false,
            excludeAmbiguous = false,
        )
        val rules = options.rules(24)
        assertEquals(24, rules.length)
        assertEquals(setOf(PasswordGenerator.CharSet.UPPER, PasswordGenerator.CharSet.DIGIT), rules.sets)
        assertEquals(false, rules.excludeAmbiguous)
    }
}
