package com.zz.passwordmanager.di

import android.content.Context
import com.zz.passwordmanager.data.crypto.Argon2KeyManager
import com.zz.passwordmanager.data.crypto.KeyManager
import com.zz.passwordmanager.data.db.VaultDatabaseProvider
import com.zz.passwordmanager.data.entries.EntryRepositoryImpl
import com.zz.passwordmanager.data.header.KdfParams
import com.zz.passwordmanager.data.header.VaultHeaderStore
import com.zz.passwordmanager.data.vault.AuthThrottle
import com.zz.passwordmanager.data.vault.ChangeMasterPassword
import com.zz.passwordmanager.data.vault.ChangeSecurityQuestions
import com.zz.passwordmanager.data.vault.DestroyVault
import com.zz.passwordmanager.data.vault.InitializeVault
import com.zz.passwordmanager.data.vault.ResetVault
import com.zz.passwordmanager.data.vault.UnlockVault
import com.zz.passwordmanager.data.vault.VaultSessionManager
import com.zz.passwordmanager.data.vault.VaultStatus
import com.zz.passwordmanager.domain.entries.EntryRepository
import com.zz.passwordmanager.platform.AndroidClipboardBridge
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
    ): DestroyVault = DestroyVault(store, provider)

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
