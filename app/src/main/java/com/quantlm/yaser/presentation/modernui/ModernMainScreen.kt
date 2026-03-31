package com.quantlm.yaser.presentation.modernui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.quantlm.yaser.presentation.Screen
import com.quantlm.yaser.presentation.appsettings.AppSettingsScreen
import com.quantlm.yaser.presentation.chat.ChatScreen
import com.quantlm.yaser.presentation.chat.ChatViewModel
import com.quantlm.yaser.presentation.models.ModelsScreen
import com.quantlm.yaser.presentation.settings.SettingsScreen
import com.quantlm.yaser.presentation.settings.SettingsViewModel

@Composable
fun ModernMainScreen() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ModernDestinations.Chat,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(ModernDestinations.Chat) {
            val chatViewModel: ChatViewModel = hiltViewModel()
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToDownloads = {
                    navController.navigate(ModernDestinations.Models) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(ModernDestinations.Settings) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToSystemLogs = {
                    navController.navigate(ModernDestinations.SystemLogs) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(ModernDestinations.Models) {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            ModelsScreen(
                useModernChrome = true,
                onNavigateBack = { navController.popBackStack() },
                onLoadModel = { model ->
                    settingsViewModel.loadModel(
                        modelPath = model.filePath,
                        modelName = model.name,
                        modelSize = model.size,
                        isVisionModel = model.isVisionModel,
                        mmprojPath = model.mmprojPath
                    )
                }
            )
        }
        composable(ModernDestinations.Settings) {
            SettingsScreen(
                useModernChrome = true,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAppSettings = {
                    navController.navigate(Screen.AppSettings.route)
                }
            )
        }
        composable(Screen.AppSettings.route) {
            AppSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(ModernDestinations.SystemLogs) {
            ModernSystemLogsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(ModernDestinations.Benchmark) {
            ModernModelBenchmarkScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
