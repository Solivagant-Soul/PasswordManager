package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.data.crypto.KeyManager
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * 加密备份**容器**（FR-7 / T26–T27 / D5）。
 *
 * ============================  它认识什么  ============================
 * - 帧结构（magic / 容器版本 / 格式 id / 长度）；
 * - AES-256-GCM（经 [KeyManager]，与其余密文同一套实现与同一份评审结论）；
 * - 原子落盘的时机。
 *
 * ============================  它不认识什么  ============================
 * 条目长什么样。它搬的是**不透明字节**：帧里那个 `codecId` 只是路由标签，
 * 容器既不解释它、也不持有任何 [com.zz.passwordmanager.domain.backup.BackupCodec]。
 * 新增一种备份格式 = 写一个 codec + 在 `BackupCodecRegistry` 登记，**本文件不动**
 * （技术方案 §9.2 记录的接缝，`BackupContainerTest` 里「第二个 codec 走同一个容器」
 * 那个用例用与 JSON 无关的假 codec 守住它）。
 *
 * ### 帧布局（全部为大端）
 * ```
 * 0   4  magic = "PMBK"
 * 4   1  容器版本（当前 1）
 * 5   2  codecId 字节长度（u16）
 * 7   n  codecId（UTF-8，n 字节）
 *       4  密文长度（i32，正数）
 *       …  密文 = nonce(12) ‖ ciphertext‖tag(16)
 * ```
 * 显式记长度是截断检测的关键：SAF 写入被中断、传输丢字节都会让实际长度短于声明值，
 * 此时**在解密之前**就能判定文件损坏，而不是把它交给 GCM 去报一个含糊的 tag 错误。
 */
class BackupContainer(
    private val keyManager: KeyManager,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    /**
     * 打包 → AES-GCM。返回可直接落盘的完整字节。
     *
     * [plaintext] 是编解码器的产物，容器不解析；[codecId] 只被原样写进帧头。
     */
    fun seal(codecId: String, plaintext: ByteArray, key: ByteArray): ByteArray {
        val idBytes = codecId.encodeToByteArray()
        require(idBytes.size <= CODEC_ID_MAX) { "codecId too long: ${idBytes.size} > $CODEC_ID_MAX" }
        val sealed = keyManager.encrypt(plaintext, key)
        val frame = ByteArrayOutputStream(HEADER_FIXED + idBytes.size + INT_BYTES + sealed.size)
        frame.write(MAGIC)
        frame.write(CONTAINER_VERSION)
        frame.write((idBytes.size ushr 8) and 0xFF)
        frame.write(idBytes.size and 0xFF)
        frame.write(idBytes)
        frame.write((sealed.size ushr 24) and 0xFF)
        frame.write((sealed.size ushr 16) and 0xFF)
        frame.write((sealed.size ushr 8) and 0xFF)
        frame.write(sealed.size and 0xFF)
        frame.write(sealed)
        return frame.toByteArray()
    }

    /**
     * 解析帧 → AES-GCM 解密。返回格式 id 与内容字节，**不做任何内容解释**。
     *
     * @throws BackupFormatException 帧结构不对（不是本容器 / 版本不认识 / 截断 / 尾部多出字节）
     * @throws BackupKeyException 认证标签不匹配——主密码错误**或**密文被篡改
     */
    fun open(frame: ByteArray, key: ByteArray): OpenedBackup {
        if (frame.size < HEADER_FIXED + INT_BYTES + MIN_SEALED_BYTES) {
            throw BackupFormatException("file too short to be a backup")
        }
        for (i in MAGIC.indices) {
            if (frame[i] != MAGIC[i]) throw BackupFormatException("not a backup file (bad magic)")
        }
        val version = frame[MAGIC.size].toInt() and 0xFF
        if (version != CONTAINER_VERSION) {
            throw BackupFormatException("unsupported container version: $version")
        }
        var offset = MAGIC.size + 1
        val idLength = ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)
        offset += 2
        if (idLength == 0 || offset + idLength + INT_BYTES > frame.size) {
            throw BackupFormatException("truncated frame header")
        }
        val codecId = frame.copyOfRange(offset, offset + idLength).decodeToString()
        offset += idLength

        val sealedLength = ((frame[offset].toInt() and 0xFF) shl 24) or
            ((frame[offset + 1].toInt() and 0xFF) shl 16) or
            ((frame[offset + 2].toInt() and 0xFF) shl 8) or
            (frame[offset + 3].toInt() and 0xFF)
        offset += INT_BYTES
        // 长度不符 = 截断或被追加了垃圾。**必须在解密前判掉**：长度域本身没有认证，
        // 让它进 GCM 只会把「文件坏了」报成「口令错了」，把用户引向错误的排查方向。
        if (sealedLength <= 0 || offset + sealedLength != frame.size) {
            throw BackupFormatException("sealed length mismatch (declared $sealedLength, actual ${frame.size - offset})")
        }

        val sealed = frame.copyOfRange(offset, frame.size)
        val plaintext = try {
            keyManager.decrypt(sealed, key)
        } catch (e: AEADBadTagException) {
            throw BackupKeyException(e)
        } catch (e: IllegalArgumentException) {
            // KeyManager 对过短密文抛 IAE；同样属于「这不是个合格的容器」
            throw BackupFormatException("sealed section malformed", e)
        }
        return OpenedBackup(codecId, plaintext)
    }

    /**
     * [open] 的结果：格式 id（供调用方选 codec）+ 已解密的内容字节。
     *
     * 刻意**不是** data class：内容字节是敏感量，用数据类的自动 `equals`（浅比较数组引用）
     * 会给人「值相等即内容相同」的错觉，而它的语义其实是「一次性载荷，请尽快清零」。
     */
    class OpenedBackup(val codecId: String, val plaintext: ByteArray) {
        override fun toString(): String = "OpenedBackup(codecId=$codecId, bytes=${plaintext.size})"
    }

    companion object {
        /** "PMBK" = PassMaster Backup Kube；一眼可辨的文件头。 */
        val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        const val CONTAINER_VERSION = 1

        private const val INT_BYTES = 4
        /** magic(4) + version(1) + idLen(2) = 7 */
        private const val HEADER_FIXED = 7
        /** nonce(12) + tag(16)，比这更短的密文不可能通过 GCM */
        private const val MIN_SEALED_BYTES = 12 + 16
        private const val CODEC_ID_MAX = 0xFFFF
    }
}

/** 帧结构不对：不是本容器、版本不认识、截断、长度域撒谎。 */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 认证失败。
 *
 * 「主密码错误」与「密文被篡改」在 AEAD 下**不可区分**，且刻意不做区分——
 * 这是 D4「以能否解出为判据」的统一失败语义，也是防 oracle 的常规做法。
 * 因此文案必须同时覆盖两种可能（见 `UserMessage.kt`）。
 */
class BackupKeyException(cause: Throwable? = null) : Exception("backup authentication failed", cause)
