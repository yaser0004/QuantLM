package com.quantlm.yaser.presentation.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val permission: String? = null,
    val permissionRationale: String? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val pages = remember {
        buildList {
            add(
                OnboardingPage(
                    icon = Icons.Default.SmartToy,
                    title = "Welcome to QuantLM",
                    description = "Your private AI assistant that runs entirely on your device. No internet required, no data leaves your phone."
                )
            )
            add(
                OnboardingPage(
                    icon = Icons.Default.Psychology,
                    title = "Powerful AI Models",
                    description = "Choose from a variety of state-of-the-art language models like Phi-4, Gemma 3, Qwen, and more. Download once, use offline forever."
                )
            )
            add(
                OnboardingPage(
                    icon = Icons.Default.Image,
                    title = "Vision Capabilities",
                    description = "Some models support image understanding. Ask questions about photos, get descriptions, and more - all processed locally."
                )
            )
            // Camera permission for vision models
            add(
                OnboardingPage(
                    icon = Icons.Default.CameraAlt,
                    title = "Camera Access",
                    description = "Take photos directly to analyze with vision-capable AI models.",
                    permission = Manifest.permission.CAMERA,
                    permissionRationale = "Camera allows you to capture images for AI analysis without leaving the app."
                )
            )
            // Microphone permission for voice input
            add(
                OnboardingPage(
                    icon = Icons.Default.Mic,
                    title = "Voice Input",
                    description = "Talk to your AI assistant using voice commands for a hands-free experience.",
                    permission = Manifest.permission.RECORD_AUDIO,
                    permissionRationale = "Microphone enables voice input so you can speak instead of typing."
                )
            )
            // Storage permission (for older Android versions)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(
                    OnboardingPage(
                        icon = Icons.Default.Storage,
                        title = "Storage Access",
                        description = "QuantLM needs storage access to save AI models for offline use.",
                        permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        permissionRationale = "Models are stored locally so you can use them without internet."
                    )
                )
            }
            // Notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    OnboardingPage(
                        icon = Icons.Default.Notifications,
                        title = "Stay Updated",
                        description = "Get notifications about model downloads and important updates.",
                        permission = Manifest.permission.POST_NOTIFICATIONS,
                        permissionRationale = "We'll notify you when model downloads complete, so you can continue using your device."
                    )
                )
            }
            add(
                OnboardingPage(
                    icon = Icons.Default.Celebration,
                    title = "You're All Set!",
                    description = "Start by downloading an AI model from the Models tab. Smaller models like SmolVLM 256M or SmolLM3 Q4 are great for getting started quickly."
                )
            )
        }
    }
    
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val currentPage = pages[pagerState.currentPage]
    
    // Permission request handling
    var permissionGranted by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        currentPage.permission?.let { permission ->
            permissionGranted = permissionGranted + (permission to granted)
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
            modifier = Modifier.fillMaxSize()
        ) {
            // Skip button (top right)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (pagerState.currentPage < pages.size - 1) {
                    TextButton(onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pages.size - 1)
                        }
                    }) {
                        Text("Skip")
                    }
                }
            }
            
            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                OnboardingPageContent(
                    page = pages[page],
                    isPermissionGranted = pages[page].permission?.let { 
                        permissionGranted[it] == true 
                    },
                    onRequestPermission = {
                        pages[page].permission?.let { permission ->
                            permissionLauncher.launch(permission)
                        }
                    }
                )
            }
            
            // Bottom section with indicators and buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 32.dp)
                ) {
                    repeat(pages.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(
                                    width = if (index == pagerState.currentPage) 24.dp else 8.dp,
                                    height = 8.dp
                                )
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) 
                                        MaterialTheme.colorScheme.primary
                                    else 
                                        MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
                
                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Back button
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Back")
                        }
                    }
                    
                    // Next/Complete button
                    Button(
                        onClick = {
                            if (pagerState.currentPage == pages.size - 1) {
                                onComplete()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        modifier = Modifier.weight(if (pagerState.currentPage > 0) 1f else 1f)
                    ) {
                        Text(
                            if (pagerState.currentPage == pages.size - 1) "Get Started"
                            else "Next"
                        )
                        if (pagerState.currentPage < pages.size - 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    isPermissionGranted: Boolean?,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        
        // Title
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Description
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        // Permission section
        page.permission?.let { _ ->
            Spacer(modifier = Modifier.height(32.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPermissionGranted == true)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isPermissionGranted == true) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Permission Granted",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        page.permissionRationale?.let { rationale ->
                            Text(
                                text = rationale,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        
                        FilledTonalButton(
                            onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant Permission")
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Info that app works without permission
                        Text(
                            text = "✓ The app works without this permission",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "You can change this later in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
