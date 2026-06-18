package com.quantlm.yaser.data.local

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app lock functionality including PIN, password, pattern, and biometric authentication.
 * This is the central manager for all lock-related operations.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val appPreferences: AppPreferences,
    private val context: Context
) {
    
    companion object {
        private const val TAG = "AppLockManager"

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        const val MIN_PASSWORD_LENGTH = 6
        const val MAX_PASSWORD_LENGTH = 32
        const val PATTERN_MIN_DOTS = 4
    }
    
    /**
     * Result of an authentication attempt
     */
    sealed class AuthResult {
        object Success : AuthResult()
        data class Failure(val message: String, val attemptsRemaining: Int = -1) : AuthResult()
        object Cancelled : AuthResult()
        object BiometricNotAvailable : AuthResult()
        object BiometricNotEnrolled : AuthResult()
    }
    
    /**
     * Biometric capability status
     */
    sealed class BiometricStatus {
        object Available : BiometricStatus()
        object NotAvailable : BiometricStatus()
        object NotEnrolled : BiometricStatus()
        object SecurityUpdateRequired : BiometricStatus()
        object Unsupported : BiometricStatus()
    }
    
    private val lockoutPrefs = context.getSharedPreferences("quantlm_lock_state", Context.MODE_PRIVATE)

    // Track failed attempts for lockout; persisted so a process restart
    // (e.g. via ADB force-stop) cannot reset the counter mid-attack.
    private var failedAttempts = lockoutPrefs.getInt("failed_attempts", 0)
    private var lockoutUntil = lockoutPrefs.getLong("lockout_until", 0L)
    private val maxFailedAttempts = 5
    private val lockoutDuration = 30_000L // 30 seconds
    
    // ========== Lock Status ==========
    
    /**
     * Check if app lock is currently enabled
     */
    fun isLockEnabled(): Flow<Boolean> {
        return appPreferences.getSettings().map { it.appLockEnabled }
    }
    
    /**
     * Get current lock type
     */
    fun getLockType(): Flow<AppPreferences.AppLockType> {
        return appPreferences.getSettings().map { it.appLockType }
    }
    
    /**
     * Check if biometric is enabled for unlock
     */
    fun isBiometricEnabled(): Flow<Boolean> {
        return appPreferences.getSettings().map { it.biometricEnabled }
    }
    
    // ========== Biometric Support ==========
    
    /**
     * Check if device supports biometric authentication
     */
    fun checkBiometricStatus(): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=available")
                BiometricStatus.Available
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=no_hardware")
                BiometricStatus.NotAvailable
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=hw_unavailable")
                BiometricStatus.NotAvailable
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=not_enrolled")
                BiometricStatus.NotEnrolled
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=security_update_required")
                BiometricStatus.SecurityUpdateRequired
            }
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=unsupported")
                BiometricStatus.Unsupported
            }
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=unknown")
                BiometricStatus.NotAvailable
            }
            else -> {
                AppEventLogger.debug(component = TAG, action = "biometric_status_checked", details = "status=other")
                BiometricStatus.NotAvailable
            }
        }
    }
    
    /**
     * Check if device has face unlock capability
     */
    fun hasFaceUnlock(): Boolean {
        val biometricManager = BiometricManager.from(context)
        // Check for face authentication specifically if available on API 30+
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == 
            BiometricManager.BIOMETRIC_SUCCESS
    }
    
    /**
     * Show biometric prompt for authentication
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Unlock QuantLM",
        subtitle: String = "Use your fingerprint or face to unlock",
        negativeButtonText: String = "Use PIN/Password",
        onResult: (AuthResult) -> Unit
    ) {
        AppEventLogger.info(component = TAG, action = "biometric_prompt_shown")
        val executor = ContextCompat.getMainExecutor(context)
        
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                resetFailedAttempts()
                AppEventLogger.info(component = TAG, action = "biometric_auth_succeeded")
                onResult(AuthResult.Success)
            }
            
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                AppEventLogger.warn(
                    component = TAG,
                    action = "biometric_auth_error",
                    details = "code=$errorCode, message=${errString.toString()}"
                )
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED -> {
                        onResult(AuthResult.Cancelled)
                    }
                    BiometricPrompt.ERROR_NO_BIOMETRICS -> {
                        onResult(AuthResult.BiometricNotEnrolled)
                    }
                    BiometricPrompt.ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.ERROR_HW_UNAVAILABLE -> {
                        onResult(AuthResult.BiometricNotAvailable)
                    }
                    else -> {
                        onResult(AuthResult.Failure(errString.toString()))
                    }
                }
            }
            
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Don't count as final failure - user can retry
                AppEventLogger.debug(component = TAG, action = "biometric_auth_failed_attempt")
            }
        }
        
        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()
        
        biometricPrompt.authenticate(promptInfo)
    }
    
    // ========== PIN/Password/Pattern Authentication ==========
    
    /**
     * Verify PIN
     */
    suspend fun verifyPin(pin: String): AuthResult {
        if (isLockedOut()) {
            val remaining = (lockoutUntil - System.currentTimeMillis()) / 1000
            AppEventLogger.warn(component = TAG, action = "verify_pin_blocked_lockout", details = "remainingSeconds=$remaining")
            return AuthResult.Failure("Too many attempts. Try again in $remaining seconds")
        }
        
        val isValid = appPreferences.verifyPin(pin)
        return if (isValid) {
            resetFailedAttempts()
            AppEventLogger.info(component = TAG, action = "verify_pin_succeeded")
            AuthResult.Success
        } else {
            AppEventLogger.warn(component = TAG, action = "verify_pin_failed")
            recordFailedAttempt()
        }
    }
    
    /**
     * Verify password
     */
    suspend fun verifyPassword(password: String): AuthResult {
        if (isLockedOut()) {
            val remaining = (lockoutUntil - System.currentTimeMillis()) / 1000
            AppEventLogger.warn(component = TAG, action = "verify_password_blocked_lockout", details = "remainingSeconds=$remaining")
            return AuthResult.Failure("Too many attempts. Try again in $remaining seconds")
        }
        
        val isValid = appPreferences.verifyPassword(password)
        return if (isValid) {
            resetFailedAttempts()
            AppEventLogger.info(component = TAG, action = "verify_password_succeeded")
            AuthResult.Success
        } else {
            AppEventLogger.warn(component = TAG, action = "verify_password_failed")
            recordFailedAttempt()
        }
    }
    
    /**
     * Verify pattern (pattern is stored as comma-separated dot indices)
     */
    suspend fun verifyPattern(pattern: String): AuthResult {
        if (isLockedOut()) {
            val remaining = (lockoutUntil - System.currentTimeMillis()) / 1000
            AppEventLogger.warn(component = TAG, action = "verify_pattern_blocked_lockout", details = "remainingSeconds=$remaining")
            return AuthResult.Failure("Too many attempts. Try again in $remaining seconds")
        }
        
        val isValid = appPreferences.verifyPattern(pattern)
        return if (isValid) {
            resetFailedAttempts()
            AppEventLogger.info(component = TAG, action = "verify_pattern_succeeded")
            AuthResult.Success
        } else {
            AppEventLogger.warn(component = TAG, action = "verify_pattern_failed")
            recordFailedAttempt()
        }
    }
    
    // ========== Lock Setup ==========
    
    /**
     * Set up PIN lock
     */
    suspend fun setupPin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH || pin.length > MAX_PIN_LENGTH) {
            AppEventLogger.warn(component = TAG, action = "setup_pin_invalid_length", details = "length=${pin.length}")
            return false
        }
        if (!pin.all { it.isDigit() }) {
            AppEventLogger.warn(component = TAG, action = "setup_pin_invalid_format")
            return false
        }
        
        appPreferences.setPin(pin)
        appPreferences.setAppLockType(AppPreferences.AppLockType.PIN)
        appPreferences.setAppLockEnabled(true)
        AppEventLogger.info(component = TAG, action = "setup_pin_succeeded", details = "pinLength=${pin.length}")
        return true
    }
    
    /**
     * Set up password lock
     */
    suspend fun setupPassword(password: String): Boolean {
        if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
            AppEventLogger.warn(component = TAG, action = "setup_password_invalid_length", details = "length=${password.length}")
            return false
        }
        
        appPreferences.setPassword(password)
        appPreferences.setAppLockType(AppPreferences.AppLockType.PASSWORD)
        appPreferences.setAppLockEnabled(true)
        AppEventLogger.info(component = TAG, action = "setup_password_succeeded", details = "length=${password.length}")
        return true
    }
    
    /**
     * Set up pattern lock
     * @param pattern Comma-separated list of dot indices (e.g., "0,1,2,5,8")
     */
    suspend fun setupPattern(pattern: String): Boolean {
        val dots = pattern.split(",")
        if (dots.size < PATTERN_MIN_DOTS) {
            AppEventLogger.warn(component = TAG, action = "setup_pattern_invalid", details = "dots=${dots.size}")
            return false
        }
        
        appPreferences.setPattern(pattern)
        appPreferences.setAppLockType(AppPreferences.AppLockType.PATTERN)
        appPreferences.setAppLockEnabled(true)
        AppEventLogger.info(component = TAG, action = "setup_pattern_succeeded", details = "dots=${dots.size}")
        return true
    }
    
    /**
     * Enable/disable biometric authentication
     */
    suspend fun setBiometricEnabled(enabled: Boolean): Boolean {
        if (enabled && checkBiometricStatus() !is BiometricStatus.Available) {
            AppEventLogger.warn(component = TAG, action = "set_biometric_enabled_blocked_unavailable")
            return false
        }
        appPreferences.setBiometricEnabled(enabled)
        AppEventLogger.info(component = TAG, action = "set_biometric_enabled", details = "enabled=$enabled")
        return true
    }
    
    /**
     * Disable app lock completely
     */
    suspend fun disableLock() {
        appPreferences.clearAppLock()
        resetFailedAttempts()
        AppEventLogger.warn(component = TAG, action = "disable_lock")
    }
    
    /**
     * Change lock timeout
     */
    suspend fun setLockTimeout(seconds: Int) {
        appPreferences.setLockTimeout(seconds)
        AppEventLogger.info(component = TAG, action = "set_lock_timeout", details = "seconds=$seconds")
    }
    
    // ========== Lockout Management ==========
    
    /**
     * Check if the user is currently locked out due to failed attempts
     */
    fun isLockedOut(): Boolean {
        return System.currentTimeMillis() < lockoutUntil
    }
    
    /**
     * Get remaining lockout time in milliseconds
     */
    fun getRemainingLockoutTime(): Long {
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }
    
    private fun recordFailedAttempt(): AuthResult {
        failedAttempts++
        val remaining = maxFailedAttempts - failedAttempts
        AppEventLogger.warn(component = TAG, action = "auth_failed_attempt_recorded", details = "remainingAttempts=$remaining")

        if (remaining <= 0) {
            lockoutUntil = System.currentTimeMillis() + lockoutDuration
            failedAttempts = 0
            lockoutPrefs.edit()
                .putInt("failed_attempts", 0)
                .putLong("lockout_until", lockoutUntil)
                .apply()
            AppEventLogger.warn(component = TAG, action = "auth_lockout_started", details = "durationMs=$lockoutDuration")
            return AuthResult.Failure(
                "Too many failed attempts. Locked for 30 seconds.",
                0
            )
        }

        lockoutPrefs.edit().putInt("failed_attempts", failedAttempts).apply()
        return AuthResult.Failure(
            "Incorrect. $remaining attempts remaining.",
            remaining
        )
    }

    private fun resetFailedAttempts() {
        failedAttempts = 0
        lockoutUntil = 0L
        lockoutPrefs.edit().putInt("failed_attempts", 0).putLong("lockout_until", 0L).apply()
    }
    
    /**
     * Get validation rules for display
     */
    fun getPinRules(): String = "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
    fun getPasswordRules(): String = "Password must be $MIN_PASSWORD_LENGTH-$MAX_PASSWORD_LENGTH characters"
    fun getPatternRules(): String = "Pattern must connect at least $PATTERN_MIN_DOTS dots"
}
