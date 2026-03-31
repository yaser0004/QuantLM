package com.quantlm.yaser.presentation.appsettings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.quantlm.yaser.data.local.AppLockManager
import com.quantlm.yaser.data.local.AppPreferences
import kotlinx.coroutines.launch

@Composable
fun UnlockScreen(
    appLockManager: AppLockManager,
    lockType: AppPreferences.AppLockType,
    biometricEnabled: Boolean,
    pinLength: Int = 4,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var credential by remember { mutableStateOf("") }
    var showCredential by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var isLockedOut by remember { mutableStateOf(false) }
    var lockoutRemainingSeconds by remember { mutableIntStateOf(0) }
    var patternError by remember { mutableStateOf(false) }
    
    // Check lockout status
    LaunchedEffect(Unit) {
        isLockedOut = appLockManager.isLockedOut()
        if (isLockedOut) {
            lockoutRemainingSeconds = (appLockManager.getRemainingLockoutTime() / 1000).toInt()
        }
    }
    
    // Countdown timer for lockout
    LaunchedEffect(isLockedOut, lockoutRemainingSeconds) {
        if (isLockedOut && lockoutRemainingSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            lockoutRemainingSeconds--
            if (lockoutRemainingSeconds <= 0) {
                isLockedOut = false
            }
        }
    }
    
    // Try biometric on first launch
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled && context is FragmentActivity) {
            appLockManager.showBiometricPrompt(
                activity = context,
                title = "Unlock QuantLM",
                subtitle = "Use your biometric credential",
                onResult = { result ->
                    when (result) {
                        is AppLockManager.AuthResult.Success -> onUnlocked()
                        is AppLockManager.AuthResult.Failure -> {
                            error = result.message
                        }
                        else -> {} // User cancelled or biometric not available
                    }
                }
            )
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo / Icon
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "QuantLM is Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = when (lockType) {
                    AppPreferences.AppLockType.PIN -> "Enter your PIN to continue"
                    AppPreferences.AppLockType.PASSWORD -> "Enter your password to continue"
                    AppPreferences.AppLockType.PATTERN -> "Draw your pattern to continue"
                    else -> "Unlock to continue"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            AnimatedVisibility(visible = isLockedOut) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Too many failed attempts",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Try again in ${lockoutRemainingSeconds}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            
            when (lockType) {
                AppPreferences.AppLockType.PIN -> {
                    // PIN Keypad with dynamic length
                    PinKeypad(
                        pin = credential,
                        pinLength = pinLength,
                        onPinChange = { credential = it },
                        enabled = !isLockedOut && !isVerifying,
                        onSubmit = {
                            if (credential.length == pinLength) {
                                isVerifying = true
                                scope.launch {
                                    val result = appLockManager.verifyPin(credential)
                                    isVerifying = false
                                    when (result) {
                                        is AppLockManager.AuthResult.Success -> onUnlocked()
                                        is AppLockManager.AuthResult.Failure -> {
                                            error = result.message
                                            failedAttempts++
                                            credential = ""
                                            isLockedOut = appLockManager.isLockedOut()
                                            if (isLockedOut) {
                                                lockoutRemainingSeconds = 30
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    )
                }
                
                AppPreferences.AppLockType.PATTERN -> {
                    // Pattern Lock Grid
                    PatternLockView(
                        modifier = Modifier.padding(16.dp),
                        gridSize = 280.dp,
                        isError = patternError,
                        enabled = !isLockedOut && !isVerifying,
                        onPatternStart = {
                            error = null
                            patternError = false
                        },
                        onPatternComplete = { pattern ->
                            isVerifying = true
                            scope.launch {
                                val result = appLockManager.verifyPattern(pattern)
                                isVerifying = false
                                when (result) {
                                    is AppLockManager.AuthResult.Success -> onUnlocked()
                                    is AppLockManager.AuthResult.Failure -> {
                                        error = result.message
                                        patternError = true
                                        failedAttempts++
                                        isLockedOut = appLockManager.isLockedOut()
                                        if (isLockedOut) {
                                            lockoutRemainingSeconds = 30
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    )
                }
                
                else -> {
                    // Password text input
                    OutlinedTextField(
                        value = credential,
                        onValueChange = { credential = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !isLockedOut && !isVerifying,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCredential = !showCredential }) {
                                Icon(
                                    imageVector = if (showCredential) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            isVerifying = true
                            scope.launch {
                                val result = appLockManager.verifyPassword(credential)
                                isVerifying = false
                                when (result) {
                                    is AppLockManager.AuthResult.Success -> onUnlocked()
                                    is AppLockManager.AuthResult.Failure -> {
                                        error = result.message
                                        failedAttempts++
                                        credential = ""
                                        isLockedOut = appLockManager.isLockedOut()
                                        if (isLockedOut) {
                                            lockoutRemainingSeconds = 30
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        },
                        enabled = credential.isNotEmpty() && !isLockedOut && !isVerifying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Unlock")
                        }
                    }
                }
            }
            
            // Error message
            AnimatedVisibility(visible = error != null) {
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            
            // Biometric button
            if (biometricEnabled) {
                Spacer(modifier = Modifier.height(32.dp))
                
                OutlinedButton(
                    onClick = {
                        if (context is FragmentActivity) {
                            appLockManager.showBiometricPrompt(
                                activity = context,
                                title = "Unlock QuantLM",
                                subtitle = "Use your biometric credential",
                                onResult = { result ->
                                    when (result) {
                                        is AppLockManager.AuthResult.Success -> onUnlocked()
                                        is AppLockManager.AuthResult.Failure -> {
                                            error = result.message
                                        }
                                        else -> {}
                                    }
                                }
                            )
                        }
                    },
                    enabled = !isLockedOut
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use Biometric")
                }
            }
        }
    }
}

@Composable
private fun PinKeypad(
    pin: String,
    pinLength: Int,
    onPinChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // PIN dots display - only show the exact number of dots for the PIN length
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            repeat(pinLength) { index ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
        
        // Keypad grid
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫")
        )
        
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else {
                        KeypadButton(
                            key = key,
                            enabled = enabled,
                            onClick = {
                                when (key) {
                                    "⌫" -> {
                                        if (pin.isNotEmpty()) {
                                            onPinChange(pin.dropLast(1))
                                        }
                                    }
                                    else -> {
                                        if (pin.length < pinLength) {
                                            val newPin = pin + key
                                            onPinChange(newPin)
                                            if (newPin.length == pinLength) {
                                                onSubmit()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    key: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val isBackspace = key == "⌫"
    
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(
                if (isBackspace) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isBackspace) {
            Icon(
                imageVector = Icons.Default.Backspace,
                contentDescription = "Backspace",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        } else {
            Text(
                text = key,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface 
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
