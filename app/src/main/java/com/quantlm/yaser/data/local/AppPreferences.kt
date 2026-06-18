package com.quantlm.yaser.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * App-wide preferences including UI style, app lock settings, and onboarding status.
 * Separate from GenerationPreferences which handles LLM generation settings.
 */
class AppPreferences(private val context: Context) {
    
    companion object {
        private const val TAG = "AppPreferences"

        // App Lock Settings
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val APP_LOCK_TYPE = stringPreferencesKey("app_lock_type")
        private val APP_LOCK_PIN = stringPreferencesKey("app_lock_pin")
        private val APP_LOCK_PIN_LENGTH = intPreferencesKey("app_lock_pin_length")
        private val APP_LOCK_PASSWORD = stringPreferencesKey("app_lock_password")
        private val APP_LOCK_PATTERN = stringPreferencesKey("app_lock_pattern")
        private val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        
        // Onboarding
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val FIRST_LAUNCH_TIME = longPreferencesKey("first_launch_time")
        
        // Background lock timeout (in seconds)
        private val LOCK_TIMEOUT = intPreferencesKey("lock_timeout")

        private val USE_MODERN_UI = booleanPreferencesKey("use_modern_ui")

        // Theme settings
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val THEME_COLOR_SOURCE = stringPreferencesKey("theme_color_source")

        // Diagnostics export counter — incremented every time the user
        // taps "Save to device" in the System Logs screen. Persisted so it
        // survives app restarts (so log-N stays monotonic across sessions).
        private val LOGS_EXPORT_COUNT = intPreferencesKey("logs_export_count")

        // Defaults
        const val DEFAULT_LOCK_TIMEOUT = 60 // 1 minute
    }
    
    /**
     * App lock types
     */
    enum class AppLockType(val value: String, val displayName: String) {
        NONE("none", "None"),
        PIN("pin", "PIN"),
        PASSWORD("password", "Password"),
        PATTERN("pattern", "Pattern")
    }

    enum class ThemeMode(val value: String, val displayName: String) {
        FOLLOW_SYSTEM("system", "Follow system"),
        LIGHT("light", "Light"),
        DARK("dark", "Dark")
    }

    enum class ThemeColorSource(val value: String, val displayName: String) {
        FOLLOW_SYSTEM("system", "Follow system"),
        SUNSET("sunset", "Sunset"),
        MONOCHROME("monochrome", "Monochrome"),
        ROSE_TAUPE("rose_taupe", "Rose Taupe"),
        CRIMSON_ORCHID("crimson_orchid", "Crimson Orchid"),
        CLAY_CYAN("clay_cyan", "Clay Cyan"),
        FOREST_MOSS("forest_moss", "Forest Moss"),
        SAGE_STONE("sage_stone", "Sage Stone"),
        // Image 1 – pastel duos
        SOFT_LAVENDER("soft_lavender", "Lavender"),
        BUBBLEGUM("bubblegum", "Bubblegum"),
        PEACH_TANGERINE("peach_tangerine", "Peach Tangerine"),
        MINT_LIME("mint_lime", "Mint Lime"),
        SKY_TEAL("sky_teal", "Sky Teal"),
        BLUE_LAVENDER("blue_lavender", "Blue Lavender"),
        ROSE_BLUSH("rose_blush", "Rose Blush"),
        // Image 2 – muted pastels
        WARM_SAND("warm_sand", "Warm Sand"),
        SAGE_MIST("sage_mist", "Sage Mist"),
        DUSTY_LAVENDER("dusty_lavender", "Dusty Lavender"),
        SOFT_SAGE("soft_sage", "Soft Sage"),
        PERIWINKLE("periwinkle", "Periwinkle"),
        OLIVE_GROVE("olive_grove", "Olive"),
        POWDER_BLUE("powder_blue", "Powder Blue"),
        // Image 3 – bright pastels
        SALMON("salmon", "Salmon"),
        BLUSH("blush", "Blush"),
        APRICOT("apricot", "Apricot"),
        AMBER("amber", "Amber"),
        APPLE_GREEN("apple_green", "Apple Green"),
        CORNFLOWER("cornflower", "Cornflower"),
        LILAC("lilac", "Lilac")
    }
    
    /**
     * Complete app settings data class
     */
    data class AppSettings(
        val appLockEnabled: Boolean = false,
        val appLockType: AppLockType = AppLockType.NONE,
        val biometricEnabled: Boolean = false,
        val onboardingCompleted: Boolean = false,
        val firstLaunchTime: Long = 0L,
        val lockTimeout: Int = DEFAULT_LOCK_TIMEOUT,
        val pinLength: Int = 4,
        val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
        val themeColorSource: ThemeColorSource = ThemeColorSource.FOLLOW_SYSTEM,
        val useModernUi: Boolean = false
    )
    
    /**
     * Get current app settings as a Flow
     */
    fun getSettings(): Flow<AppSettings> {
        return context.appSettingsDataStore.data.map { preferences ->
            AppSettings(
                appLockEnabled = preferences[APP_LOCK_ENABLED] ?: false,
                appLockType = AppLockType.entries.find { 
                    it.value == preferences[APP_LOCK_TYPE] 
                } ?: AppLockType.NONE,
                biometricEnabled = preferences[BIOMETRIC_ENABLED] ?: false,
                onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: false,
                firstLaunchTime = preferences[FIRST_LAUNCH_TIME] ?: 0L,
                lockTimeout = preferences[LOCK_TIMEOUT] ?: DEFAULT_LOCK_TIMEOUT,
                pinLength = preferences[APP_LOCK_PIN_LENGTH] ?: 4,
                themeMode = ThemeMode.entries.find {
                    it.value == preferences[THEME_MODE]
                } ?: ThemeMode.FOLLOW_SYSTEM,
                themeColorSource = ThemeColorSource.entries.find {
                    it.value == preferences[THEME_COLOR_SOURCE]
                } ?: ThemeColorSource.FOLLOW_SYSTEM,
                useModernUi = preferences[USE_MODERN_UI] ?: false
            )
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[THEME_MODE] = mode.value
        }
        AppEventLogger.info(component = TAG, action = "set_theme_mode", details = "mode=${mode.value}")
    }

    suspend fun setThemeColorSource(source: ThemeColorSource) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[THEME_COLOR_SOURCE] = source.value
        }
        AppEventLogger.info(component = TAG, action = "set_theme_color_source", details = "source=${source.value}")
    }
    
    // ========== App Lock Methods ==========
    
    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[APP_LOCK_ENABLED] = enabled
        }
        AppEventLogger.info(component = TAG, action = "set_app_lock_enabled", details = "enabled=$enabled")
    }
    
    suspend fun setAppLockType(type: AppLockType) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[APP_LOCK_TYPE] = type.value
        }
        AppEventLogger.info(component = TAG, action = "set_app_lock_type", details = "type=${type.value}")
    }
    
    suspend fun setPin(pin: String) {
        context.appSettingsDataStore.edit { preferences ->
            // Store salted-KDF hash (see CredentialHasher) and PIN length for UI
            preferences[APP_LOCK_PIN] = CredentialHasher.hash(pin)
            preferences[APP_LOCK_PIN_LENGTH] = pin.length
        }
        AppEventLogger.info(component = TAG, action = "set_pin", details = "pinLength=${pin.length}")
    }

    suspend fun setPassword(password: String) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[APP_LOCK_PASSWORD] = CredentialHasher.hash(password)
        }
        AppEventLogger.info(component = TAG, action = "set_password", details = "passwordLength=${password.length}")
    }

    suspend fun setPattern(pattern: String) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[APP_LOCK_PATTERN] = CredentialHasher.hash(pattern)
        }
        AppEventLogger.info(component = TAG, action = "set_pattern", details = "patternLength=${pattern.length}")
    }
    
    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[BIOMETRIC_ENABLED] = enabled
        }
        AppEventLogger.info(component = TAG, action = "set_biometric_enabled", details = "enabled=$enabled")
    }
    
    suspend fun setLockTimeout(seconds: Int) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[LOCK_TIMEOUT] = seconds
        }
        AppEventLogger.info(component = TAG, action = "set_lock_timeout", details = "seconds=$seconds")
    }
    
    suspend fun verifyPin(pin: String): Boolean = verifyCredential(APP_LOCK_PIN, pin)

    suspend fun verifyPassword(password: String): Boolean =
        verifyCredential(APP_LOCK_PASSWORD, password)

    suspend fun verifyPattern(pattern: String): Boolean =
        verifyCredential(APP_LOCK_PATTERN, pattern)

    /**
     * Verify [input] against the stored hash for [key]. Runs on
     * [Dispatchers.Default] — 150k PBKDF2 rounds are deliberately slow and
     * must not run on the Main thread. On a successful match against a legacy
     * unsalted SHA-256 value, the stored hash is transparently re-written in
     * the salted v2 format, so the weak hash leaves disk at the user's next
     * successful unlock without any reset or lockout.
     */
    private suspend fun verifyCredential(
        key: Preferences.Key<String>,
        input: String
    ): Boolean = withContext(Dispatchers.Default) {
        val stored = context.appSettingsDataStore.data.first()[key] ?: return@withContext false
        val ok = CredentialHasher.matches(input, stored)
        if (ok && CredentialHasher.isLegacy(stored)) {
            context.appSettingsDataStore.edit { it[key] = CredentialHasher.hash(input) }
            AppEventLogger.info(
                component = TAG,
                action = "credential_hash_upgraded",
                details = "key=${key.name}"
            )
        }
        ok
    }
    
    fun getStoredPin(): Flow<String?> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[APP_LOCK_PIN]
        }
    }
    
    fun getStoredPassword(): Flow<String?> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[APP_LOCK_PASSWORD]
        }
    }
    
    fun getStoredPattern(): Flow<String?> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[APP_LOCK_PATTERN]
        }
    }
    
    // ========== Onboarding Methods ==========
    
    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED] = completed
            if (completed) {
                preferences[FIRST_LAUNCH_TIME] = System.currentTimeMillis()
            }
        }
        AppEventLogger.info(component = TAG, action = "set_onboarding_completed", details = "completed=$completed")
    }
    
    fun isOnboardingCompleted(): Flow<Boolean> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[ONBOARDING_COMPLETED] ?: false
        }
    }

    suspend fun setUseModernUi(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[USE_MODERN_UI] = enabled
        }
        AppEventLogger.info(component = TAG, action = "set_use_modern_ui", details = "enabled=$enabled")
    }
    
    // ========== Utility Methods ==========

    /**
     * Clear all app lock credentials
     */
    suspend fun clearAppLock() {
        context.appSettingsDataStore.edit { preferences ->
            preferences[APP_LOCK_ENABLED] = false
            preferences[APP_LOCK_TYPE] = AppLockType.NONE.value
            preferences.remove(APP_LOCK_PIN)
            preferences.remove(APP_LOCK_PASSWORD)
            preferences.remove(APP_LOCK_PATTERN)
            preferences[BIOMETRIC_ENABLED] = false
        }
        AppEventLogger.warn(component = TAG, action = "clear_app_lock")
    }
    
    /**
     * Reset all app preferences to defaults
     */
    suspend fun resetAll() {
        context.appSettingsDataStore.edit { preferences ->
            preferences.clear()
        }
        AppEventLogger.warn(component = TAG, action = "reset_all_preferences")
    }

    // ========== Diagnostics export counter ==========

    /**
     * Atomically increment the diagnostics-export counter and return the
     * new value. Used by the "Save to device" button in System Logs so each
     * saved Markdown file carries a monotonic log number (log-1, log-2, …)
     * across the lifetime of the install.
     */
    suspend fun nextLogsExportNumber(): Int {
        var next = 1
        context.appSettingsDataStore.edit { preferences ->
            next = (preferences[LOGS_EXPORT_COUNT] ?: 0) + 1
            preferences[LOGS_EXPORT_COUNT] = next
        }
        AppEventLogger.info(
            component = TAG,
            action = "logs_export_counter_incremented",
            details = "next=$next"
        )
        return next
    }

    /** Read-only view of the current export counter. */
    fun observeLogsExportCount(): Flow<Int> {
        return context.appSettingsDataStore.data.map { it[LOGS_EXPORT_COUNT] ?: 0 }
    }
}
