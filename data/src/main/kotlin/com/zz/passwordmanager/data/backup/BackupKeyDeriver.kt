package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.header.VaultHeaderStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份密钥派生（FR-7 / v1.3.0 用户决策：**用主密码，没有独立备份口令**）。
 *
 * 做法是对主密码再跑一次 Argon2id，但换一个**域分离过的盐**：
 * `saltKdf ‖ 0x00 ‖ "pmw-backup-v1"`。
 *
 * 为什么不直接拿 `KEK_master`（`saltKdf` 原样）来加密备份：那是同一把密钥的第二个用途。
 * 一旦备份文件泄露，攻击者拿到的就是能解开 DEK 包裹的密钥，跨用途复用把两个防线叠成了一个。
 * 加一段域后缀后，两把密钥在计算上互不相关，而派生成本只多一次 Argon2（备份是低频操作）。
 *
 * ### 由此产生的、必须讲给用户听的后果
 * 盐里含 `saltKdf`，而 `saltKdf` 在**改主密码**（`ChangeMasterPassword`）与
 * **密保找回重置**（`ResetVault`）时都会重新随机生成。因此**这两件事都会让此前的备份
 * 永久无法解开**——原主密码不可取回，密钥也就再也算不出来。
 * 备份子页必须把这句话写出来（见 `BackupSettingScreen`）。
 *
 * 好处是备份文件对「拿到数据库文件的人」也毫无用处：解开它需要主密码本身，
 * 而不只是数据库里那把 DEK。
 */
class BackupKeyDeriver(
    private val keyManager: KeyManager,
    private val headerStore: VaultHeaderStore,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * 主密码 → 备份密钥。调用方负责在用完后清零返回的字节与入参。
     *
     * @throws BackupIoException 密码库尚未初始化（没有 header 就没有盐）
     */
    suspend fun derive(masterPassword: CharArray): ByteArray = withContext(computeDispatcher) {
        val header = headerStore.read()
            ?: throw BackupIoException("密码库尚未初始化")
        val salt = domainSeparatedSalt(header.saltKdf)
        try {
            keyManager.deriveKek(masterPassword, salt, header.kdfParams)
        } finally {
            // 口令的 UTF-8 中转副本由 KeyManager 内部清零（Argon2KeyManager 的既有纪律）
            salt.fill(0)
        }
    }

    private fun domainSeparatedSalt(saltKdf: ByteArray): ByteArray =
        saltKdf + 0 + DOMAIN.toByteArray(Charsets.US_ASCII)

    private companion object {
        /** 域标签。用 ASCII 且以 \u0000 结尾，保证与任意 saltKdf 拼接后无歧义。 */
        const val DOMAIN = "pmw-backup-v1\u0000"
    }
}
