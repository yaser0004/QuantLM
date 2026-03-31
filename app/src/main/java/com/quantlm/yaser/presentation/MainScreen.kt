package com.quantlm.yaser.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import com.quantlm.yaser.presentation.modernui.ModernMainScreen

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Models : Screen("models", "Models", Icons.Default.Memory)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object AppSettings : Screen("app_settings", "App Settings")
}

@Composable
fun MainScreen() {
    ModernMainScreen()
}
