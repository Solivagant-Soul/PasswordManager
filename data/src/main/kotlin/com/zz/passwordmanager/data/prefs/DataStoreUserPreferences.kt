package com.zz.passwordmanager.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zz.passwordmanager.domain.autolock.AutoLockDuration
import com.zz.passwordmanager.domain.password.GeneratorOptions
import com.zz.passwordmanager.domain.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user-prefs")

/**
 * DataStore 偏好实现（T39）。
 *
 * 选 DataStore 而非 SharedPreferences：需要类型安全的 key 与协程 API，
 * 与项目全协程的数据层一致；SharedPreferences 的同步阻塞读在主线程上是隐患。
 *
 * **偏好是明文无敏感的**（只有时长与长度），但仍放在应用私有目录，
 * 不进备份——已在 dataExtractionRules 中排除。
 */
class DataStoreUserPreferences(context: Context) : UserPreferences {

    private val store = context.applicationContext.dataStore

    override val autoLockDuration: Flow<AutoLockDuration> = store.data.map { prefs ->
        val raw = prefs[KEY_AUTO_LOCK] ?: DEFAULT_AUTO_LOCK.name
        // 未知值回退默认档，而不是抛异常——偏好文件损坏不该让 App 起不来
        AutoLockDuration.entries.firstOrNull { it.name == raw } ?: DEFAULT_AUTO_LOCK
    }

    override val defaultPasswordLength: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_DEFAULT_LENGTH] ?: DEFAULT_LENGTH
    }

    /**
     * v2.1/D4：五个开关各自独立取值，**缺键一律回落 true**——
     * 用 `prefs[KEY] ?: true` 而非 `prefs[KEY] ?: false`，否则首次安装拿到空字符集。
     */
    override val generatorOptions: Flow<GeneratorOptions> = store.data.map { prefs ->
        GeneratorOptions(
            useUpper = prefs[KEY_USE_UPPER] ?: true,
            useLower = prefs[KEY_USE_LOWER] ?: true,
            useDigit = prefs[KEY_USE_DIGIT] ?: true,
            useSymbol = prefs[KEY_USE_SYMBOL] ?: true,
            excludeAmbiguous = prefs[KEY_EXCLUDE_AMBIGUOUS] ?: true,
        )
    }

    override suspend fun setAutoLockDuration(duration: AutoLockDuration) {
        store.edit { it[KEY_AUTO_LOCK] = duration.name }
    }

    override suspend fun setGeneratorOptions(options: GeneratorOptions) {
        store.edit {
            it[KEY_USE_UPPER] = options.useUpper
            it[KEY_USE_LOWER] = options.useLower
            it[KEY_USE_DIGIT] = options.useDigit
            it[KEY_USE_SYMBOL] = options.useSymbol
            it[KEY_EXCLUDE_AMBIGUOUS] = options.excludeAmbiguous
        }
    }

    override suspend fun setDefaultPasswordLength(length: Int) {
        store.edit { it[KEY_DEFAULT_LENGTH] = length.coerceIn(MIN_LENGTH, MAX_LENGTH) }
    }

    private companion object {
        val KEY_AUTO_LOCK = stringPreferencesKey("auto_lock_duration")
        val KEY_DEFAULT_LENGTH = intPreferencesKey("default_password_length")
        val KEY_USE_UPPER = booleanPreferencesKey("gen_use_upper")
        val KEY_USE_LOWER = booleanPreferencesKey("gen_use_lower")
        val KEY_USE_DIGIT = booleanPreferencesKey("gen_use_digit")
        val KEY_USE_SYMBOL = booleanPreferencesKey("gen_use_symbol")
        val KEY_EXCLUDE_AMBIGUOUS = booleanPreferencesKey("gen_exclude_ambiguous")
        val DEFAULT_AUTO_LOCK = AutoLockDuration.M1
        const val DEFAULT_LENGTH = 16
        const val MIN_LENGTH = 4
        // 与 PasswordGenerator.LENGTH_MAX 对齐：否则用户能存进 33，打开生成器页却被夹断
        const val MAX_LENGTH = 32
    }
}
