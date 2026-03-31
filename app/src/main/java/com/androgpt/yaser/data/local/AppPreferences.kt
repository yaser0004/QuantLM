package com.quantlm.yaser.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
                useModernUi = preferences[USE_MODERN_UI] ?: false
            )
        }
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
            // Store hashed PIN for security and PIN length for UI
            preferences[APP_LOCK_PIN] = hashCode(pin)
            preferences[APP_LOCK_PIN_LENGTH] = pin.length
        }
        AppEventLogger.info(component = TAG, action = "set_pin", details = "pinLength=${pin.length}")
    }
    
    suspend fun setPassword(password: String) {
        context.appSettingsDataStore.edit { preferences ->
            // Store hashed password for security
            preferences[APP_LOCK_PASSWORD] = hashCode(password)
        }
        AppEventLogger.info(component = TAG, action = "set_password", details = "passwordLength=${password.length}")
    }
    
    suspend fun setPattern(pattern: String) {
        context.appSettingsDataStore.edit { preferences ->
            // Store hashed pattern for security
            preferences[APP_LOCK_PATTERN] = hashCode(pattern)
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
    
    fun verifyPin(pin: String): Flow<Boolean> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[APP_LOCK_PIN] == hashCode(pin)
        }
    }
    
    fun verifyPassword(password: String): Flow<Boolean> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[APP_LOCK_PASSWORD] == hashCode(password)
        }
    }
    
    fun verifyPattern(pattern: String): Flow<Boolean> {
        return context.appSettingsDataStore.data.map { preferences ->
            preferences[APP_LOCK_PATTERN] == hashCode(pattern)
        }
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
     * Simple hash function for storing credentials securely.
     * In production, use a proper crypto library with salt.
     */
    private fun hashCode(input: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray())
            digest.fold("") { str, byte -> str + "%02x".format(byte) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
    
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
}
