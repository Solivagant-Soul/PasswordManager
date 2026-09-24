package com.zz.passwordmanager.di

import android.content.Context
import com.zz.passwordmanager.data.biometric.BiometricToggle
import com.zz.passwordmanager.data.biometric.UnlockWithBiometric
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
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.domain.preferences.UserPreferences
import com.zz.passwordmanager.domain.security.SecurityScanner
import com.zz.passwordmanager.platform.AndroidClipboardBridge
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
}
