package com.zz.passwordmanager.di

import android.content.Context
import com.zz.passwordmanager.data.biometric.BiometricToggle
import com.zz.passwordmanager.data.biometric.UnlockWithBiometric
import com.zz.passwordmanager.data.backup.BackupCodecRegistry
import com.zz.passwordmanager.data.backup.BackupContainer
import com.zz.passwordmanager.data.backup.BackupKeyDeriver
import com.zz.passwordmanager.data.backup.ExportBackup
import com.zz.passwordmanager.data.backup.ImportBackup
import com.zz.passwordmanager.data.backup.JsonBackupCodec
import com.zz.passwordmanager.data.backup.RoomBackupVault
import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.entries.CategoryRepository
import com.zz.passwordmanager.data.entries.EntryRepositoryImpl
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.prefs.DataStoreUserPreferences
import com.zz.passwordmanager.data.security.DefaultSecurityScanner
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.data.vault.AutofillVaultReader
import com.zz.passwordmanager.data.vault.AutofillVaultSession
import com.zz.passwordmanager.data.vault.RoomAutofillVaultReader
import com.zz.passwordmanager.data.vault.ChangeMasterPassword
import com.zz.passwordmanager.data.vault.ChangeSecurityQuestions
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.ReadSecurityQuestions
import com.zz.passwordmanager.data.vault.ResetVault
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultStatus
import com.zz.passwordmanager.domain.biometric.BiometricDekStore
import com.zz.passwordmanager.domain.biometric.BiometricGate
import com.zz.passwordmanager.domain.backup.BackupVault
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.preferences.UserPreferences
import com.zz.passwordmanager.domain.security.SecurityScanner
import com.zz.passwordmanager.platform.AndroidClipboardBridge
import com.zz.passwordmanager.ui.BackupStoreFactory
import com.zz.passwordmanager.ui.SafBackupStoreFactory
import com.zz.passwordmanager.platform.biometric.AndroidBiometricGate
import com.zz.passwordmanager.platform.biometric.KeystoreDekVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.SecureRandom
import javax.inject.Singleton

/** M2 DI 接线：全部单例/用例的构造组装（构造参数与方案 §5 一致）。 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Header MAC 密钥：首次随机生成，持久于私有目录；Keystore 托管升级属 T29（评审 G-C/G-G）。 */
    @Provides
    @Singleton
    fun provideHeaderMacKey(@ApplicationContext context: Context): ByteArray {
        val file = File(context.filesDir, "header-mac.key")
        return if (file.exists()) {
            file.readBytes()
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also(file::writeBytes)
        }
    }

    @Provides
    @Singleton
    fun provideHeaderStore(@ApplicationContext context: Context, macKey: ByteArray): VaultHeaderStore =
        VaultHeaderStore(context.filesDir, macKey)

    @Provides
    @Singleton
    fun provideVaultStatus(store: VaultHeaderStore): VaultStatus = VaultStatus(store)

    @Provides
    @Singleton
    fun provideKeyManager(): KeyManager = Argon2KeyManager()

    @Provides
    @Singleton
    fun provideThrottle(): AuthThrottle = AuthThrottle(System::currentTimeMillis)

    @Provides
    @Singleton
    fun provideSessionManager(): VaultSessionManager = VaultSessionManager()

    @Provides
    @Singleton
    fun provideDatabaseProvider(@ApplicationContext context: Context): VaultDatabaseProvider =
        VaultDatabaseProvider(context)

    @Provides
    fun provideKdfParams(): KdfParams = Argon2KeyManager.DEFAULT_KDF_PARAMS

    @Provides
    fun provideInitializeVault(
        keyManager: KeyManager,
        store: VaultHeaderStore,
        kdfParams: KdfParams,
    ): InitializeVault = InitializeVault(keyManager, store, kdfParams = kdfParams)

    @Provides
    fun provideUnlockVault(keyManager: KeyManager, store: VaultHeaderStore, throttle: AuthThrottle): UnlockVault =
        UnlockVault(keyManager, store, throttle)

    /**
     * FR-6 自动填充的自持会话（v1.2.0）。
     *
     * 单例是**必需**而非图省事：服务只递 Intent、真正解锁的是认证 Activity，
     * 两者要共享同一份「这一次」的解锁状态；进程被杀则单例随之消失，
     * 不会有跨进程的残留。
     *
     * 刻意不注入 [VaultSessionManager]：注入它就等于把自动填充接回 UI 那条长会话，
     * 正是技术方案 §9.2 明令禁止的那条旁路。
     */
    @Provides
    @Singleton
    fun provideAutofillVaultSession(
        unlockVault: UnlockVault,
        reader: AutofillVaultReader,
    ): AutofillVaultSession = AutofillVaultSession(unlockVault, reader)

    @Provides
    @Singleton
    fun provideAutofillVaultReader(
        provider: VaultDatabaseProvider,
        keyManager: KeyManager,
    ): AutofillVaultReader = RoomAutofillVaultReader(provider, keyManager)

    @Provides
    fun provideResetVault(keyManager: KeyManager, store: VaultHeaderStore, throttle: AuthThrottle): ResetVault =
        ResetVault(keyManager, store, throttle)

    @Provides
    fun provideChangeMasterPassword(
        keyManager: KeyManager,
        store: VaultHeaderStore,
        throttle: AuthThrottle,
    ): ChangeMasterPassword = ChangeMasterPassword(keyManager, store, throttle)

    /** FR-12 密保找回入口：只读问题文本供 UI 展示。 */
    @Provides
    fun provideReadSecurityQuestions(store: VaultHeaderStore): ReadSecurityQuestions =
        ReadSecurityQuestions(store)

    @Provides
    fun provideChangeSecurityQuestions(
        keyManager: KeyManager,
        store: VaultHeaderStore,
        throttle: AuthThrottle,
    ): ChangeSecurityQuestions = ChangeSecurityQuestions(keyManager, store, throttle)

    /** G-B 清除重建入口（破坏性，UI 层负责二次确认）。 */
    @Provides
    fun provideDestroyVault(
        store: VaultHeaderStore,
        provider: VaultDatabaseProvider,
        dekStore: BiometricDekStore,
    ): DestroyVault = DestroyVault(store, provider, dekStore)

    // ---- FR-2 生物识别快捷解锁（V1-4） ----

    @Provides
    @Singleton
    fun provideBiometricGate(@ApplicationContext context: Context): BiometricGate =
        AndroidBiometricGate(context)

    @Provides
    @Singleton
    fun provideBiometricDekStore(): BiometricDekStore = KeystoreDekVault()

    @Provides
    fun provideBiometricToggle(
        keyManager: KeyManager,
        store: VaultHeaderStore,
        throttle: AuthThrottle,
        gate: BiometricGate,
        dekStore: BiometricDekStore,
    ): BiometricToggle = BiometricToggle(keyManager, store, throttle, gate, dekStore)

    @Provides
    fun provideUnlockWithBiometric(
        store: VaultHeaderStore,
        throttle: AuthThrottle,
        dekStore: BiometricDekStore,
        gate: BiometricGate,
    ): UnlockWithBiometric = UnlockWithBiometric(store, throttle, dekStore, gate)

    @Provides
    @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferences =
        DataStoreUserPreferences(context)

    @Provides
    @Singleton
    fun provideSecurityScanner(repository: EntryRepository): SecurityScanner =
        DefaultSecurityScanner(repository)

    @Provides
    @Singleton
    fun provideCategoryRepository(
        provider: VaultDatabaseProvider,
        sessionManager: VaultSessionManager,
    ): CategoryRepository = CategoryRepository(provider, sessionManager)

    @Provides
    @Singleton
    fun provideEntryRepository(
        provider: VaultDatabaseProvider,
        sessionManager: VaultSessionManager,
        keyManager: KeyManager,
    ): EntryRepository = EntryRepositoryImpl(provider, sessionManager, keyManager)

    @Provides
    @Singleton
    fun provideClipboardBridge(@ApplicationContext context: Context): AndroidClipboardBridge =
        AndroidClipboardBridge(context)

    // ---- FR-7 加密备份导出/导入（v1.3.0 / D5） ----

    /**
     * 备份的**整库读写端口**。见 `BackupVault` 的 KDoc：刻意与 `EntryRepository` 分开，
     * 免得 UI 层拿到一把能一次搬走全库明文的钥匙。
     *
     * 注入它的是本文件里的两个备份用例，`:app` 侧**不直接注入**——UI 拿到的只有
     * [ExportBackup] / [ImportBackup]，明文只在用例内部、用后即清。
     */
    @Provides
    @Singleton
    fun provideBackupVault(
        provider: VaultDatabaseProvider,
        sessionManager: VaultSessionManager,
        keyManager: KeyManager,
    ): BackupVault = RoomBackupVault(provider, sessionManager, keyManager)

    @Provides
    @Singleton
    fun provideBackupContainer(keyManager: KeyManager): BackupContainer = BackupContainer(keyManager)

    /**
     * 格式登记表（技术方案 §9.2 的接缝）。二期加 CSV 只需在这里**追加一个 codec**，
     * 容器与导入流程一行都不用改。
     */
    @Provides
    @Singleton
    fun provideBackupCodecRegistry(): BackupCodecRegistry = BackupCodecRegistry(listOf(JsonBackupCodec()))

    @Provides
    @Singleton
    fun provideBackupKeyDeriver(keyManager: KeyManager, store: VaultHeaderStore): BackupKeyDeriver =
        BackupKeyDeriver(keyManager, store)

    @Provides
    fun provideExportBackup(
        vault: BackupVault,
        container: BackupContainer,
        registry: BackupCodecRegistry,
        keyDeriver: BackupKeyDeriver,
    ): ExportBackup = ExportBackup(vault, container, registry.default, keyDeriver)

    @Provides
    fun provideImportBackup(
        vault: BackupVault,
        container: BackupContainer,
        registry: BackupCodecRegistry,
        keyDeriver: BackupKeyDeriver,
    ): ImportBackup = ImportBackup(vault, container, registry, keyDeriver)

    /**
     * SAF 读写端点。抽成接口是为了让备份子页的**编排逻辑**（预检 → 确认 → 提交）
     * 能在 JVM 单测里跑——`:app` 没有 `src/androidTest`，真 `ContentResolver` 跑不动。
     */
    @Provides
    @Singleton
    fun provideBackupStoreFactory(@ApplicationContext context: Context): BackupStoreFactory =
        SafBackupStoreFactory(context)
}
