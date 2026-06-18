package com.quantlm.yaser.presentation

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import com.quantlm.yaser.data.local.AppLockManager
import com.quantlm.yaser.data.local.AppPreferences
import com.quantlm.yaser.presentation.appsettings.UnlockScreen
import com.quantlm.yaser.presentation.onboarding.OnboardingScreen
import com.quantlm.yaser.presentation.theme.QuantLMTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : androidx.fragment.app.FragmentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }
    
    @Inject
    lateinit var appPreferences: AppPreferences
    
    @Inject
    lateinit var appLockManager: AppLockManager
    
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // NOTE: Notification permission is now requested in the onboarding tutorial
        // instead of here, so users are informed about what it's for before granting.

        setContent {
            val settings by appPreferences.getSettings().collectAsState(
                initial = AppPreferences.AppSettings()
            )

            QuantLMTheme(
                themeMode = settings.themeMode,
                colorSource = settings.themeColorSource
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    // Expose Compose testTag values as resource IDs so Firebase
                    // Test Lab Robo scripts can target elements by resourceId.
                    modifier = Modifier.semantics { testTagsAsResourceId = true }
                ) {

                    // Use rememberSaveable to persist unlock state across configuration changes
                    // This prevents the app from asking for credentials again on orientation change
                    var isUnlocked by rememberSaveable { mutableStateOf(false) }
                    var onboardingCompleted by remember { mutableStateOf<Boolean?>(null) }
                    
                    // Cache lock-related settings to avoid triggering recomposition on unrelated changes
                    val appLockEnabled = settings.appLockEnabled
                    val appLockType = settings.appLockType
                    val biometricEnabled = settings.biometricEnabled
                    val pinLength = settings.pinLength
                    
                    // Load initial onboarding state
                    LaunchedEffect(Unit) {
                        onboardingCompleted = appPreferences.getSettings().first().onboardingCompleted
                    }
                    
                    // Only include values that determine which screen to show
                    // This prevents settings changes from recreating MainScreen
                    AnimatedContent(
                        targetState = Triple(
                            onboardingCompleted,
                            appLockEnabled && !isUnlocked,
                            Unit // Placeholder - we use derived values below
                        ),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "app_state"
                    ) { (hasCompleted, needsUnlock, _) ->
                        when {
                            // Still loading onboarding state
                            hasCompleted == null -> {
                                // Show a loading state or nothing
                                Surface(color = MaterialTheme.colorScheme.background) {}
                            }
                            // Show onboarding if not completed
                            !hasCompleted -> {
                                OnboardingScreen(
                                    onComplete = {
                                        MainScope().launch {
                                            appPreferences.setOnboardingCompleted(true)
                                            onboardingCompleted = true
                                        }
                                    }
                                )
                            }
                            // Show lock screen if app lock is enabled and not unlocked
                            needsUnlock -> {
                                UnlockScreen(
                                    appLockManager = appLockManager,
                                    lockType = appLockType,
                                    biometricEnabled = biometricEnabled,
                                    pinLength = pinLength,
                                    onUnlocked = { isUnlocked = true }
                                )
                            }
                            // Show main screen
                            else -> {
                                MainScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
