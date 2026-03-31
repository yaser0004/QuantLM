package com.quantlm.yaser.presentation.appsettings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.local.AppLockManager
import com.quantlm.yaser.data.local.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val appLockManager: AppLockManager
) : ViewModel() {
    
    // App Lock Settings
    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()
    
    private val _appLockType = MutableStateFlow(AppPreferences.AppLockType.NONE)
    val appLockType: StateFlow<AppPreferences.AppLockType> = _appLockType.asStateFlow()
    
    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()
    
    private val _biometricStatus = MutableStateFlow<AppLockManager.BiometricStatus>(
        AppLockManager.BiometricStatus.NotAvailable
    )
    val biometricStatus: StateFlow<AppLockManager.BiometricStatus> = _biometricStatus.asStateFlow()
    
    private val _lockTimeout = MutableStateFlow(AppPreferences.DEFAULT_LOCK_TIMEOUT)
    val lockTimeout: StateFlow<Int> = _lockTimeout.asStateFlow()

    private val _themeMode = MutableStateFlow(AppPreferences.ThemeMode.FOLLOW_SYSTEM)
    val themeMode: StateFlow<AppPreferences.ThemeMode> = _themeMode.asStateFlow()

    private val _themeColorSource = MutableStateFlow(AppPreferences.ThemeColorSource.FOLLOW_SYSTEM)
    val themeColorSource: StateFlow<AppPreferences.ThemeColorSource> = _themeColorSource.asStateFlow()
    
    // UI State
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    
    private val _showLockSetupDialog = MutableStateFlow<AppPreferences.AppLockType?>(null)
    val showLockSetupDialog: StateFlow<AppPreferences.AppLockType?> = _showLockSetupDialog.asStateFlow()
    
    init {
        loadSettings()
        checkBiometricStatus()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            appPreferences.getSettings().collect { settings ->
                _appLockEnabled.value = settings.appLockEnabled
                _appLockType.value = settings.appLockType
                _biometricEnabled.value = settings.biometricEnabled
                _lockTimeout.value = settings.lockTimeout
                _themeMode.value = settings.themeMode
                _themeColorSource.value = settings.themeColorSource
            }
        }
    }
    
    private fun checkBiometricStatus() {
        _biometricStatus.value = appLockManager.checkBiometricStatus()
    }
    
    // ========== App Lock ==========
    
    fun showLockSetup(type: AppPreferences.AppLockType) {
        _showLockSetupDialog.value = type
    }
    
    fun dismissLockSetup() {
        _showLockSetupDialog.value = null
    }
    
    fun setupPin(pin: String) {
        viewModelScope.launch {
            val success = appLockManager.setupPin(pin)
            if (success) {
                _appLockEnabled.value = true
                _appLockType.value = AppPreferences.AppLockType.PIN
                _message.value = "PIN lock enabled successfully"
                dismissLockSetup()
            } else {
                _message.value = appLockManager.getPinRules()
            }
        }
    }
    
    fun setupPassword(password: String) {
        viewModelScope.launch {
            val success = appLockManager.setupPassword(password)
            if (success) {
                _appLockEnabled.value = true
                _appLockType.value = AppPreferences.AppLockType.PASSWORD
                _message.value = "Password lock enabled successfully"
                dismissLockSetup()
            } else {
                _message.value = appLockManager.getPasswordRules()
            }
        }
    }
    
    fun setupPattern(pattern: String) {
        viewModelScope.launch {
            val success = appLockManager.setupPattern(pattern)
            if (success) {
                _appLockEnabled.value = true
                _appLockType.value = AppPreferences.AppLockType.PATTERN
                _message.value = "Pattern lock enabled successfully"
                dismissLockSetup()
            } else {
                _message.value = appLockManager.getPatternRules()
            }
        }
    }
    
    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val success = appLockManager.setBiometricEnabled(enabled)
            if (success) {
                _biometricEnabled.value = enabled
                _message.value = if (enabled) "Biometric unlock enabled" else "Biometric unlock disabled"
            } else {
                _message.value = "Biometric authentication not available on this device"
            }
        }
    }
    
    fun disableAppLock() {
        viewModelScope.launch {
            appLockManager.disableLock()
            _appLockEnabled.value = false
            _appLockType.value = AppPreferences.AppLockType.NONE
            _biometricEnabled.value = false
            _message.value = "App lock disabled"
        }
    }
    
    fun setLockTimeout(seconds: Int) {
        viewModelScope.launch {
            appLockManager.setLockTimeout(seconds)
            _lockTimeout.value = seconds
        }
    }

    fun setThemeMode(mode: AppPreferences.ThemeMode) {
        viewModelScope.launch {
            appPreferences.setThemeMode(mode)
            _themeMode.value = mode
            _message.value = when (mode) {
                AppPreferences.ThemeMode.FOLLOW_SYSTEM -> "Theme mode set to follow system"
                AppPreferences.ThemeMode.LIGHT -> "Theme mode set to light"
                AppPreferences.ThemeMode.DARK -> "Theme mode set to dark"
            }
        }
    }

    fun setThemeColorSource(source: AppPreferences.ThemeColorSource) {
        viewModelScope.launch {
            appPreferences.setThemeColorSource(source)
            _themeColorSource.value = source
            _message.value = if (source == AppPreferences.ThemeColorSource.FOLLOW_SYSTEM) {
                if (isDynamicColorSupported()) {
                    "Color palette set to follow system wallpaper"
                } else {
                    "Color palette set to system fallback"
                }
            } else {
                "Color palette updated"
            }
        }
    }
    
    // ========== Utility ==========
    
    fun clearMessage() {
        _message.value = null
    }
    
    fun getBiometricStatusText(): String {
        return when (_biometricStatus.value) {
            is AppLockManager.BiometricStatus.Available -> "Available"
            is AppLockManager.BiometricStatus.NotAvailable -> "Not available on this device"
            is AppLockManager.BiometricStatus.NotEnrolled -> "No biometrics enrolled - set up in device settings"
            is AppLockManager.BiometricStatus.SecurityUpdateRequired -> "Security update required"
            is AppLockManager.BiometricStatus.Unsupported -> "Not supported"
        }
    }
    
    fun isBiometricAvailable(): Boolean {
        return _biometricStatus.value is AppLockManager.BiometricStatus.Available
    }

    fun isDynamicColorSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
