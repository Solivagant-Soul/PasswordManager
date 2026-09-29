package com.zz.passwordmanager.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Utf8] 的无 String 中转编解码验收。
 *
 * 这段代码存在的全部理由是「不产生不可清零的 String 副本」，所以测试的重点是
 * **与 String 参考实现逐字节一致**——一致就意味着没有行为变化，
 * 换来的纯粹是内存卫生。
 */
class Utf8Test {

    @Test
    fun `encode matches String encodeToByteArray for representative inputs`() {
        listOf(
            "",
            "simple-ascii",
            "P@ssw0rd!",
            "中文密码",
            "пароль-Ω",
            "emoji-🔐-🗝️-mixed",
            "surrogate-pair-🔐",
        ).forEach { text ->
            assertArrayEquals(
                "encode mismatch for <$text>",
                text.encodeToByteArray(),
                Utf8.encode(text.toCharArray()),
            )
        }
    }

    @Test
    fun `decode matches String decodeToString for representative inputs`() {
        listOf(
            "",
            "simple-ascii",
            "中文密码",
            "emoji-🔐-🗝️",
            "mixed 中 a 🔐 b",
        ).forEach { text ->
            assertEquals(
                "decode mismatch for <$text>",
                text,
                Utf8.decode(text.encodeToByteArray()).concatToString(),
            )
        }
    }

    @Test
    fun `round trip preserves every character`() {
        val original = "🔐 mixed 中文 ascii 🔐 end".toCharArray()
        assertArrayEquals(original, Utf8.decode(Utf8.encode(original)))
    }

    @Test
    fun `lone surrogate is replaced not thrown`() {
        // 备份文件里不该出现代理项；万一出现（文件被人手改过），
        // 期望的是「能读出来一个替代字符」而不是整个导入崩掉
        val lone = charArrayOf('a', '\uD800', 'b')
        val decoded = Utf8.decode(Utf8.encode(lone))
        assertEquals(3, decoded.size)
        assertEquals('a', decoded[0])
        assertEquals('b', decoded[2])
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, Utf8.encode(CharArray(0)).size)
        assertEquals(0, Utf8.decode(ByteArray(0)).size)
    }
}
