package com.zz.passwordmanager.data.vault

import com.zz.passwordmanager.data.header.VaultHeaderStore

/**
 * 读取密保问题文本（FR-12 找回入口用）。
 *
 * 只取**问题**，不涉及答案——答案从不落盘（D4），能否用答案解出 DEK 才是「答对/答错」的判据。
 * 读取 Header 可能抛 [com.zz.passwordmanager.data.header.HeaderIntegrityException]，
 * 由调用方按 G-B 损坏路径处理（宁严勿松，不静默进入找回流程）。
 */
class ReadSecurityQuestions(private val headerStore: VaultHeaderStore) {

    operator fun invoke(): List<String> =
        headerStore.read()
            ?.securityQuestions
            ?.sortedBy { it.position }
            ?.map { it.questionText }
            ?: emptyList()
}
