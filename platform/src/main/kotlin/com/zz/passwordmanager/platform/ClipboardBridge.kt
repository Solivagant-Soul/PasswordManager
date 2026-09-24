package com.zz.passwordmanager.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 剪贴板桥（FR-9/T17）：复制敏感值 + 30 秒后「只清自己复制的内容」（D9）。
 *
 * 清理判定：到期时若剪贴板仍是本次复制的内容（hash 相同）才清空；
 * 用户在此期间复制了其他内容则不动——避免误伤。
 * 取代 T02 占位接口：:app 直接使用本实现（评审 G：不过度抽象）。
 */
class AndroidClipboardBridge(
    private val context: Context,
    private val clearDelayMs: Long = CLEAR_DELAY_MS,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var pendingToken: Int? = null

    fun copySensitive(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        pendingToken = text.hashCode()
        mainHandler.postDelayed({ clearIfUnchanged() }, clearDelayMs)
    }

    /** 清理判定（内部 + 测试入口）：剪贴板仍为本会话复制内容时清空。 */
    fun clearIfUnchanged() {
        val token = pendingToken ?: return
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount == 1 && clip.getItemAt(0).text?.hashCode() == token) {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, ""))
        }
        pendingToken = null
    }

    private companion object {
        const val CLEAR_DELAY_MS = 30_000L // BR-8（Q-03）
        const val CLIP_LABEL = "password-manager"
    }
}
