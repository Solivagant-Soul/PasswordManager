package com.zz.passwordmanager.domain.backup

/**
 * 备份对密码库的整库读写（FR-7）。
 *
 * **为什么不长在 [com.zz.passwordmanager.domain.entries.EntryRepository] 上**（v1.3.0 决策）：
 * `EntryRepository` 是面向 UI 的 CRUD 端口，它的敏感面被 T17/NFR-S4 刻意收窄到
 * 「一次一条、且必须经 [com.zz.passwordmanager.domain.entries.EntryRepository.revealPassword]
 * 显式揭示」。加一个「导出全部明文」的方法等于给 UI 层发一把能一次搬走整个密码库的钥匙，
 * 绕过揭示态约束，也让日后任何误调用都变成一次全量明文外泄。
 *
 * 备份是另一个入口、另一套生命周期（一次性、批处理、用后即清），
 * 因此单列一个窄端口：只有备份用例能拿到它，UI 拿不到。
 */
interface BackupVault {

    /** 全量读出为备份内容（含明文密码）。调用方负责 [BackupPayload.clear]。 */
    suspend fun readAll(): BackupPayload

    /**
     * 整库替换（清空 + 写入）。**全有或全无**：失败时库必须与调用前逐字节一致。
     *
     * 实现必须自带事务边界——「校验」由调用方（`ImportBackup`）在**进事务前**做完，
     * 这里只保证「事务内失败不留半替换的库」。
     *
     * [payload] 的所有权在调用期间**完全交给本方法**：实现不得把它的 `CharArray`
     * 引用留存到返回之后（该按实体落库就落库）。调用方据此在返回后立即
     * `clear()` 整份明文（NFR-S4），若实现留了引用，那次清零就会连带抹掉已入库的数据。
     */
    suspend fun replaceAll(payload: BackupPayload): Result<Unit>
}
