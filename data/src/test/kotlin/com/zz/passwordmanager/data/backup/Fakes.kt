package com.zz.passwordmanager.data.backup

import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeader
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.domain.backup.BackupPayload
import com.zz.passwordmanager.domain.backup.BackupRecord
import com.zz.passwordmanager.domain.backup.BackupVault
import com.zz.passwordmanager.domain.entries.Category
import com.zz.passwordmanager.domain.entries.EntryType
import java.io.File

/**
 * 测试替身：一个**真的持有数据**的 [BackupVault]。
 *
 * 刻意不做成 `mockk(relaxed = true)`：全有或全无的验收标准是「库里的东西还在不在」，
 * 只有替身自己存一份条目快照，这个断言才有意义。mock 只能证明「某个方法被调用过」，
 * 那与「数据完好」是两回事。
 */
class FakeBackupVault(
    initial: List<BackupRecord> = emptyList(),
    private val categories: List<Category> = emptyList(),
) : BackupVault {

    var stored: List<BackupRecord> = initial
        private set

    /** [replaceAll] 的调用次数。预检类用例靠它断言「密码库一次都没被碰」。 */
    var replaceAllCalls: Int = 0
        private set

    /** 让提交阶段失败（模拟 DB 侧故障），用于验证失败被如实上报。 */
    var failReplaceWith: Throwable? = null

    override suspend fun readAll(): BackupPayload = BackupPayload(stored, categories)

    override suspend fun replaceAll(payload: BackupPayload): Result<Unit> {
        replaceAllCalls++
        failReplaceWith?.let { return Result.failure(it) }
        // **必须深拷贝 CharArray**。真实实现（`RoomBackupVault`）在事务前就把每条口令
        // 加密成密文实体，不会对 `BackupRecord` 的数组留任何引用。若这个替身直接存实例，
        // `ImportBackup.apply` 提交后按 NFR-S4 清零明文时就会把「已经存进库里」的内容
        // 一并抹成 NUL——那是替身独有的别名 bug，不是生产行为，断言会红得莫名其妙。
        stored = payload.records.map { record ->
            record.copy(
                password = record.password.copyOf(),
                totpSecret = record.totpSecret?.copyOf(),
            )
        }
        return Result.success(Unit)
    }

    /** 快照：调用方用它对比「导入前后库内容是否一致」。 */
    fun snapshot(): List<String> = stored.map {
        listOf(it.id, it.title, it.username, it.password.concatToString()).joinToString("|")
    }
}

/** 供多个测试文件复用的记录构造。 */
internal fun record(
    id: String,
    title: String = "站点$id",
    username: String = "user$id",
    password: String = "P@ss-$id",
    url: String? = null,
    notes: String? = null,
    categoryId: String? = null,
    totpSecret: String? = null,
    favorite: Boolean = false,
    type: EntryType = EntryType.ACCOUNT,
    createdAt: Long = 0L,
    updatedAt: Long = 0L,
): BackupRecord = BackupRecord(
    id = id, title = title, username = username, password = password.toCharArray(),
    url = url, notes = notes, categoryId = categoryId,
    totpSecret = totpSecret?.toCharArray(), favorite = favorite, type = type,
    createdAt = createdAt, updatedAt = updatedAt,
)

/**
 * 一条龙的真实组件装配：容器 + JSON codec + Argon2 派生 + 本地文件落盘，
 * 只把 KDF 参数换成小值（否则一次派生就是几百毫秒到数秒，单测没法跑）。
 * 用的是**生产代码本身**，不是替身——这正是这些用例的价值所在。
 */
internal class BackupTestHarness(
    directory: File,
    masterPassword: String = MASTER_PASSWORD,
    /** 改这个值就模拟「改主密码 / 密保找回重置」换过盐 */
    val saltKdf: ByteArray = ByteArray(16) { 7 },
) {
    val headerStore = VaultHeaderStore(directory, ByteArray(32) { it.toByte() })
    val keyManager = Argon2KeyManager(recoveryKdfParams = SMALL_KDF)
    val container = BackupContainer(keyManager)
    val codec = JsonBackupCodec()
    val registry = BackupCodecRegistry(listOf(codec))
    val keyDeriver = BackupKeyDeriver(keyManager, headerStore)

    init {
        // 备份密钥只依赖 saltKdf + kdfParams，wrappedDek* 在这里用不到真值
        headerStore.write(
            VaultHeader(
                saltKdf = saltKdf,
                kdfParams = SMALL_KDF,
                wrappedDekMaster = ByteArray(48),
                wrappedDekRecovery = ByteArray(48),
                saltRecovery = ByteArray(16),
            ),
        )
    }

    fun exporter(vault: BackupVault) = ExportBackup(
        backupVault = vault,
        container = container,
        codec = codec,
        keyDeriver = keyDeriver,
    )

    fun importer(vault: BackupVault, maxEntries: Int = 5_000) = ImportBackup(
        backupVault = vault,
        container = container,
        registry = registry,
        keyDeriver = keyDeriver,
        maxEntries = maxEntries,
    )

    /** 用当前盐与主密码直接封一个任意内容（用于构造「格式合法但内容是垃圾」的备份）。 */
    suspend fun sealRaw(codecId: String, plaintext: ByteArray, password: String = MASTER_PASSWORD): ByteArray =
        container.seal(codecId, plaintext, keyDeriver.derive(password.toCharArray()))

    companion object {
        const val MASTER_PASSWORD = "correct-horse-battery-staple"

        /** 与 `EntryRepositoryImplTest` 同款的小参数：单测里 Argon2 必须快。 */
        val SMALL_KDF = KdfParams(iterations = 1, memoryKib = 8, parallelism = 1)
    }
}
