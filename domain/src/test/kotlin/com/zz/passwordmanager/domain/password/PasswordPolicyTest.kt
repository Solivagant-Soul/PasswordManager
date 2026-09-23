package com.zz.passwordmanager.domain.password

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {

    private fun issuesOf(password: String) = PasswordPolicy.issues(password.toCharArray())

    @Test
    fun `min length boundary is accepted`() {
        // 8 位，小写 + 数字两类
        assertTrue(issuesOf("abcd1234").isEmpty())
    }

    @Test
    fun `below min length is rejected`() {
        assertTrue(issuesOf("abc1234").contains(PasswordIssue.TOO_SHORT))
    }

    @Test
    fun `single char class is rejected`() {
        assertTrue(issuesOf("abcdefgh").contains(PasswordIssue.SINGLE_CLASS))
        assertTrue(issuesOf("12345678").contains(PasswordIssue.SINGLE_CLASS))
        assertTrue(issuesOf("ABCDEFGH").contains(PasswordIssue.SINGLE_CLASS))
    }

    @Test
    fun `blank only password is rejected`() {
        val issues = issuesOf("        ")

        assertTrue(issues.contains(PasswordIssue.BLANK_ONLY))
        assertTrue(issues.contains(PasswordIssue.SINGLE_CLASS))
    }

    @Test
    fun `control chars are rejected`() {
        assertTrue(issuesOf("abc1234\u0000").contains(PasswordIssue.CONTROL_CHARS))
    }

    @Test
    fun `whitespace does not count as char class`() {
        // 9 位，空格分隔的小写 + 数字：两类，通过
        assertTrue(issuesOf("a b c d 1").isEmpty())
    }

    @Test
    fun `unicode letters count as symbol class`() {
        // 全角/中文字符（S8 应可接受）：符号 + 数字 = 两类
        assertTrue(issuesOf("密码测试1234").isEmpty())
    }

    @Test
    fun `four classes each count`() {
        assertEquals(emptySet<PasswordIssue>(), issuesOf("Abcdefg1!"))
    }
}
