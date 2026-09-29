package com.zz.passwordmanager.data.backup

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * [CharArray] ↔ UTF-8 字节的无 `String` 中转转换。
 *
 * **为什么不直接用 `chars.concatToString().encodeToByteArray()`**：那会在堆上留下一份
 * 不可清零的 `String` 明文，直到 GC 回收为止。项目已有的做法（`Argon2KeyManager`、
 * `EntryRepositoryImpl.revealPassword`）都是「用后清零 ByteArray + 承认 String 副本
 * 是 JVM 固有限制」。备份链路把这条限制收得更紧：一次导出可能有 5000 条密码，
 * 若每条都经一次 String 中转，就是 5000 份明文在堆上排队等 GC——这不是理论风险。
 *
 * 因此这里走 `CharsetEncoder`/`CharsetDecoder`：
 * `CharBuffer.wrap(chars)` 直接借用调用方的 CharArray（零拷贝），
 * 编解码结果落进我们自己分配的 ByteBuffer/CharBuffer，全程不产生 String。
 * 解码侧的 CharBuffer 由本对象分配并交出其底层数组（`array()`），
 * 调用方拿到的就是那个可清零的 CharArray。
 *
 * 非法序列的策略与 `String` 的默认行为一致：编码端替换为 `?`（REPLACE），
 * 解码端替换为 U+FFFD。备份文件里的口令是我们自己写出去的，合法输入不会走到这里；
 * 走到的唯一来源是被人手改过的文件，此时「能读出来」比「拒绝」更可用。
 */
internal object Utf8 {

    fun encode(chars: CharArray): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val inBuf = CharBuffer.wrap(chars)
        // UTF-8 最多 3 字节/字符（代理对 4 字节/2 字符 = 2 字节/字符），留 4 倍余量避免循环扩容
        val initial = (chars.size * 4).coerceAtLeast(16)
        var out = ByteBuffer.allocate(initial)
        while (true) {
            val result = encoder.encode(inBuf, out, true)
            if (result.isOverflow) {
                out = grow(out)
                continue
            }
            // endOfInput=true 时正常只会是 UNDERFLOW；此分支只为兜住实现异常
            out.flip()
            return ByteArray(out.remaining()).also { out.get(it) }
        }
    }

    fun decode(bytes: ByteArray): CharArray {
        // 每个 UTF-8 字符至少占 1 字节，故字符数不超过字节数——首轮按此分配即可命中；
        // 万一不够（实现异常）就翻倍重来，绝不返回被截断的结果。
        var capacity = bytes.size.coerceAtLeast(1)
        while (true) {
            val out = CharBuffer.allocate(capacity)
            val result = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes), out, true)
            if (!result.isOverflow) {
                out.flip()
                return CharArray(out.remaining()).also { out.get(it) }
            }
            capacity *= 2
        }
    }

    private fun grow(buffer: ByteBuffer): ByteBuffer {
        val bigger = ByteBuffer.allocate(buffer.capacity() * 2 + 16)
        buffer.flip()
        bigger.put(buffer)
        return bigger
    }
}
