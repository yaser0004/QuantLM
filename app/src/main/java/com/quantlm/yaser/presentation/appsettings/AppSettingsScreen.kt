package com.quantlm.yaser.presentation.appsettings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.quantlm.yaser.data.local.AppPreferences
import com.quantlm.yaser.presentation.theme.ThemePaletteOption
import com.quantlm.yaser.presentation.theme.ThemePaletteRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppSettingsViewModel = hiltViewModel()
) {
    com.quantlm.yaser.presentation.util.LogScreenLifecycle("AppSettingsScreen")
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
    val appLockType by viewModel.appLockType.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val lockTimeout by viewModel.lockTimeout.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val themeColorSource by viewModel.themeColorSource.collectAsState()
    val message by viewModel.message.collectAsState()
    val showLockSetupDialog by viewModel.showLockSetupDialog.collectAsState()
    val dynamicColorSupported = remember { viewModel.isDynamicColorSupported() }

    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ThemeAppearanceCard(
                themeMode = themeMode,
                themeColorSource = themeColorSource,
                dynamicColorSupported = dynamicColorSupported,
                onThemeModeChange = viewModel::setThemeMode,
                onThemeColorSourceChange = viewModel::setThemeColorSource
            )

            // App Lock Section
            AppLockCard(
                appLockEnabled = appLockEnabled,
                appLockType = appLockType,
                biometricEnabled = biometricEnabled,
                isBiometricAvailable = viewModel.isBiometricAvailable(),
                biometricStatusText = viewModel.getBiometricStatusText(),
                lockTimeout = lockTimeout,
                onSetupLock = viewModel::showLockSetup,
                onDisableLock = viewModel::disableAppLock,
                onBiometricToggle = viewModel::setBiometricEnabled,
                onLockTimeoutChange = viewModel::setLockTimeout
            )
        }
    }
    
    // Lock Setup Dialog
    showLockSetupDialog?.let { type ->
        LockSetupDialog(
            lockType = type,
            onDismiss = viewModel::dismissLockSetup,
            onConfirm = { credential ->
                when (type) {
                    AppPreferences.AppLockType.PIN -> viewModel.setupPin(credential)
                    AppPreferences.AppLockType.PASSWORD -> viewModel.setupPassword(credential)
                    AppPreferences.AppLockType.PATTERN -> viewModel.setupPattern(credential)
                    else -> {}
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeAppearanceCard(
    themeMode: AppPreferences.ThemeMode,
    themeColorSource: AppPreferences.ThemeColorSource,
    dynamicColorSupported: Boolean,
    onThemeModeChange: (AppPreferences.ThemeMode) -> Unit,
    onThemeColorSourceChange: (AppPreferences.ThemeColorSource) -> Unit
) {
    val palettes = remember { ThemePaletteRegistry.manualOptions }

    SettingsCard(
        title = "Appearance & Theme",
        icon = Icons.Default.Palette
    ) {
        Text(
            text = "Choose light/dark behavior and decide whether colors follow your system or a manual palette.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Theme mode",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = AppPreferences.ThemeMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        Text(
                            text = when (mode) {
                                AppPreferences.ThemeMode.FOLLOW_SYSTEM -> "System"
                                AppPreferences.ThemeMode.LIGHT -> "Light"
                                AppPreferences.ThemeMode.DARK -> "Dark"
                            }
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Color palette",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        FilterChip(
            selected = themeColorSource == AppPreferences.ThemeColorSource.FOLLOW_SYSTEM,
            onClick = {
                onThemeColorSourceChange(AppPreferences.ThemeColorSource.FOLLOW_SYSTEM)
            },
            label = {
                Text(
                    if (dynamicColorSupported) {
                        "Follow device system (Material You)"
                    } else {
                        "Follow device system"
                    }
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            palettes.forEach { option ->
                PaletteOptionButton(
                    option = option,
                    selected = themeColorSource == option.source,
                    onClick = { onThemeColorSourceChange(option.source) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = when {
                themeColorSource == AppPreferences.ThemeColorSource.FOLLOW_SYSTEM && dynamicColorSupported ->
                    "System palette active: colors follow wallpaper and device theme settings."
                themeColorSource == AppPreferences.ThemeColorSource.FOLLOW_SYSTEM && !dynamicColorSupported ->
                    "Material You is unavailable on this device. System palette uses QuantLM base colors."
                else ->
                    "Manual palette active: selected colors are used regardless of device Material You support."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PaletteOptionButton(
    option: ThemePaletteOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                .padding(if (selected) 4.dp else 2.dp)
                .clip(CircleShape)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(option.buttonColors.top)
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(option.buttonColors.bottomStart)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(option.buttonColors.bottomEnd)
                    )
                }
            }
        }

        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun AppLockCard(
    appLockEnabled: Boolean,
    appLockType: AppPreferences.AppLockType,
    biometricEnabled: Boolean,
    isBiometricAvailable: Boolean,
    biometricStatusText: String,
    lockTimeout: Int,
    onSetupLock: (AppPreferences.AppLockType) -> Unit,
    onDisableLock: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onLockTimeoutChange: (Int) -> Unit
) {
    var showLockTypeMenu by remember { mutableStateOf(false) }
    var showTimeoutMenu by remember { mutableStateOf(false) }
    
    SettingsCard(
        title = "App Lock",
        icon = Icons.Default.Lock
    ) {
        Text(
            text = "Protect your conversations with a lock",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Lock Type Selection
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { showLockTypeMenu = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (appLockType) {
                    AppPreferences.AppLockType.NONE -> Icons.Default.LockOpen
                    AppPreferences.AppLockType.PIN -> Icons.Default.Pin
                    AppPreferences.AppLockType.PASSWORD -> Icons.Default.Password
                    AppPreferences.AppLockType.PATTERN -> Icons.Default.Pattern
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Lock Type",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (appLockEnabled) appLockType.displayName else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            DropdownMenu(
                expanded = showLockTypeMenu,
                onDismissRequest = { showLockTypeMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Disabled") },
                    onClick = {
                        showLockTypeMenu = false
                        onDisableLock()
                    },
                    leadingIcon = { Icon(Icons.Default.LockOpen, null) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("PIN") },
                    onClick = {
                        showLockTypeMenu = false
                        onSetupLock(AppPreferences.AppLockType.PIN)
                    },
                    leadingIcon = { Icon(Icons.Default.Pin, null) }
                )
                DropdownMenuItem(
                    text = { Text("Password") },
                    onClick = {
                        showLockTypeMenu = false
                        onSetupLock(AppPreferences.AppLockType.PASSWORD)
                    },
                    leadingIcon = { Icon(Icons.Default.Password, null) }
                )
                DropdownMenuItem(
                    text = { Text("Pattern") },
                    onClick = {
                        showLockTypeMenu = false
                        onSetupLock(AppPreferences.AppLockType.PATTERN)
                    },
                    leadingIcon = { Icon(Icons.Default.Pattern, null) }
                )
            }
        }
        
        AnimatedVisibility(visible = appLockEnabled) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                
                // Biometric Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = if (isBiometricAvailable) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Biometric Unlock",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isBiometricAvailable) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = biometricStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = onBiometricToggle,
                        enabled = isBiometricAvailable
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Lock Timeout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { showTimeoutMenu = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lock Timeout",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = formatTimeout(lockTimeout),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    DropdownMenu(
                        expanded = showTimeoutMenu,
                        onDismissRequest = { showTimeoutMenu = false }
                    ) {
                        listOf(0, 30, 60, 300, 600, 1800).forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text(formatTimeout(seconds)) },
                                onClick = {
                                    showTimeoutMenu = false
                                    onLockTimeoutChange(seconds)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            content()
        }
    }
}

@Composable
private fun LockSetupDialog(
    lockType: AppPreferences.AppLockType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var credential by remember { mutableStateOf("") }
    var confirmCredential by remember { mutableStateOf("") }
    var showCredential by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isConfirmPhase by remember { mutableStateOf(false) }
    
    val title = when (lockType) {
        AppPreferences.AppLockType.PIN -> "Set up PIN"
        AppPreferences.AppLockType.PASSWORD -> "Set up Password"
        AppPreferences.AppLockType.PATTERN -> if (isConfirmPhase) "Confirm Pattern" else "Draw Pattern"
        else -> ""
    }
    
    val hint = when (lockType) {
        AppPreferences.AppLockType.PIN -> "Enter 4-8 digit PIN"
        AppPreferences.AppLockType.PASSWORD -> "Enter password (min 6 characters)"
        AppPreferences.AppLockType.PATTERN -> if (isConfirmPhase) "Draw the pattern again to confirm" else "Connect at least 4 dots"
        else -> ""
    }
    
    val keyboardType = when (lockType) {
        AppPreferences.AppLockType.PIN -> KeyboardType.NumberPassword
        AppPreferences.AppLockType.PASSWORD -> KeyboardType.Password
        else -> KeyboardType.Text
    }
    
    if (lockType == AppPreferences.AppLockType.PATTERN) {
        // Full-screen pattern setup dialog
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { 
                Text(
                    text = title,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                ) 
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    PatternLockView(
                        gridSize = 200.dp,
                        isError = error != null,
                        enabled = true,
                        onPatternStart = { error = null },
                        onPatternComplete = { pattern ->
                            val dots = pattern.split(",")
                            if (dots.size < 4) {
                                error = "Connect at least 4 dots"
                            } else if (!isConfirmPhase) {
                                credential = pattern
                                isConfirmPhase = true
                            } else {
                                if (pattern == credential) {
                                    onConfirm(credential)
                                } else {
                                    error = "Patterns don't match. Try again."
                                    isConfirmPhase = false
                                    credential = ""
                                }
                            }
                        }
                    )
                    
                    error?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    if (isConfirmPhase) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            onClick = {
                                isConfirmPhase = false
                                credential = ""
                                error = null
                            }
                        ) {
                            Text("Reset")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    } else {
        // PIN or Password dialog
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = when (lockType) {
                        AppPreferences.AppLockType.PIN -> Icons.Default.Pin
                        AppPreferences.AppLockType.PASSWORD -> Icons.Default.Password
                        else -> Icons.Default.Lock
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = credential,
                        onValueChange = { 
                            credential = it
                            error = null
                        },
                        label = { Text(if (lockType == AppPreferences.AppLockType.PIN) "PIN" else "Password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCredential = !showCredential }) {
                                Icon(
                                    imageVector = if (showCredential) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showCredential) "Hide" else "Show"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = confirmCredential,
                        onValueChange = { 
                            confirmCredential = it
                            error = null
                        },
                        label = { Text("Confirm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    error?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            credential.isEmpty() -> error = "Please enter a value"
                            credential != confirmCredential -> error = "Values don't match"
                            lockType == AppPreferences.AppLockType.PIN && (credential.length < 4 || credential.length > 8) -> 
                                error = "PIN must be 4-8 digits"
                            lockType == AppPreferences.AppLockType.PASSWORD && credential.length < 6 -> 
                                error = "Password must be at least 6 characters"
                            else -> onConfirm(credential)
                        }
                    }
                ) {
                    Text("Set")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatTimeout(seconds: Int): String {
    return when (seconds) {
        0 -> "Immediately"
        30 -> "30 seconds"
        60 -> "1 minute"
        300 -> "5 minutes"
        600 -> "10 minutes"
        1800 -> "30 minutes"
        else -> "$seconds seconds"
    }
}
