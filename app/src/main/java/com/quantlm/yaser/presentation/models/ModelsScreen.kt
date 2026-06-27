package com.quantlm.yaser.presentation.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.quantlm.yaser.presentation.ui.common.ClickableLink
import com.quantlm.yaser.presentation.ui.common.ModernGradientHeader
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.quantlm.yaser.domain.model.DownloadState
import com.quantlm.yaser.domain.model.ModelInfo
import com.quantlm.yaser.domain.model.ModelLoadingState
import com.quantlm.yaser.domain.model.isVisionModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel = hiltViewModel(),
    onLoadModel: (ModelInfo) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    useModernChrome: Boolean = false
) {
    com.quantlm.yaser.presentation.util.LogScreenLifecycle("ModelsScreen")
    val downloadViewModel: ModelDownloadViewModel = hiltViewModel()
    val availableModels by viewModel.availableModels.collectAsState()
    val loadedModel by viewModel.loadedModel.collectAsState()
    val modelLoadingState by viewModel.modelLoadingState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val downloadedModels by downloadViewModel.downloadedModels.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf<ModelInfo?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(downloadedModels) {
        viewModel.refreshModels()
    }
    
    Scaffold(
        topBar = {
            Column {
                if (useModernChrome) {
                    ModernGradientHeader(
                        title = "Models",
                        subtitle = "Local and downloadable AI models",
                        onBack = onNavigateBack
                    )
                } else {
                    TopAppBar(
                        title = { Text("Models") },
                        navigationIcon = {
                            if (onNavigateBack != null) {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        modifier = Modifier.testTag("tab_local_models"),
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Local Models") }
                    )
                    Tab(
                        modifier = Modifier.testTag("tab_get_models"),
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Get Models") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showImportDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import Model") }
                )
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> LocalModelsTab(
                availableModels = availableModels,
                loadedModel = loadedModel,
                modelLoadingState = modelLoadingState,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onLoadModel = onLoadModel,
                onUnloadModel = { viewModel.unloadModel() },
                onDeleteModel = { showDeleteDialog = it },
                onClearError = { viewModel.clearError() },
                onRefreshModels = { viewModel.refreshModels() },
                modifier = Modifier.padding(paddingValues)
            )
            1 -> ModelDownloadScreen(
                viewModel = downloadViewModel,
                onModelDownloaded = {
                    viewModel.refreshModels()
                    selectedTab = 0
                },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
    
    // Delete confirmation dialog
    showDeleteDialog?.let { model ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = { Text("Are you sure you want to delete ${model.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(model.filePath)
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import model dialog
    if (showImportDialog) {
        ModelImportDialog(
            onDismiss = { showImportDialog = false },
            onImportSuccess = {
                showImportDialog = false
                viewModel.refreshModels()
            }
        )
    }
}

@Composable
fun LocalModelsTab(
    availableModels: List<ModelInfo>,
    loadedModel: ModelInfo?,
    modelLoadingState: ModelLoadingState,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadModel: (ModelInfo) -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: (ModelInfo) -> Unit,
    onClearError: () -> Unit,
    onRefreshModels: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val downloadViewModel: ModelDownloadViewModel = hiltViewModel()
    val allModels = com.quantlm.yaser.domain.model.AvailableModels.getAllModels()
    val downloadStates by downloadViewModel.downloadStates.collectAsState()
    val completionMessage by downloadViewModel.completionMessage.collectAsState()
    
    // Create a map of file names to downloadable models for additional info
    val modelInfoMap = remember(allModels) {
        allModels.associateBy { it.fileName }
    }
    
    // Determine if any model operation is in progress
    val isModelOperationInProgress = modelLoadingState !is ModelLoadingState.Idle
    
    var showInfoDialog by remember { mutableStateOf<Pair<ModelInfo, com.quantlm.yaser.domain.model.DownloadableModel?>?>(null) }
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Show model loading status banner
        if (isModelOperationInProgress) {
            // Item 3: elapsed-time reassurance so a slow native load doesn't look
            // frozen. Keyed on the status text so the timer resets when the target
            // model changes. (The "(large model — may take 2–3 min)" hint is
            // already part of getStatusMessage.)
            val loadingStatus = modelLoadingState.getStatusMessage()
            var longRunPhase by remember(loadingStatus) { mutableStateOf(0) }
            LaunchedEffect(loadingStatus) {
                longRunPhase = 0
                delay(10_000)
                longRunPhase = 1
                delay(20_000)
                longRunPhase = 2
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = loadingStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (longRunPhase > 0) {
                            Text(
                                text = if (longRunPhase == 1)
                                    "Still loading — large models can take a minute…"
                                else
                                    "Almost there — the first load is the slowest…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
        
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        if (errorMessage != null) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = onClearError) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(errorMessage)
            }
        }
        
        // Show completion message as a snackbar-like notification
        completionMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(message)
            }
        }
        
        if (availableModels.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "No models found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Download models from the Get Models tab",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(availableModels, key = { it.filePath }) { model ->
                    // Extract filename from path
                    val fileName = model.filePath.substringAfterLast("/")
                    val downloadableModel = modelInfoMap[fileName]
                    
                    // Get mmproj download state if this is a vision model
                    val mmprojDownloadState = downloadableModel?.let { dm ->
                        if (dm.isVisionModel) {
                            downloadStates["${dm.id}_mmproj"]
                        } else null
                    }
                    
                    // Check if this model is currently being loaded
                    val isThisModelLoading = when (val state = modelLoadingState) {
                        is ModelLoadingState.Loading -> model.name == state.modelName
                        is ModelLoadingState.Switching -> model.name == state.toModel
                        is ModelLoadingState.Unloading -> {
                            val unloadingModel = state.modelName ?: loadedModel?.name
                            model.name == unloadingModel
                        }
                        else -> false
                    }
                    
                    EnhancedModelItem(
                        model = model,
                        downloadableModel = downloadableModel,
                        isLoaded = loadedModel?.filePath == model.filePath,
                        isLoadingThisModel = isThisModelLoading,
                        isAnyModelOperationInProgress = isModelOperationInProgress,
                        mmprojDownloadState = mmprojDownloadState,
                        onLoad = { onLoadModel(model) },
                        onUnload = onUnloadModel,
                        onDelete = { onDeleteModel(model) },
                        onShowInfo = { showInfoDialog = Pair(model, downloadableModel) },
                        onDownloadVision = {
                            downloadableModel?.let { dm ->
                                if (dm.isVisionModel && dm.mmprojUrl != null) {
                                    downloadViewModel.downloadMmprojOnly(dm) {
                                        // Refresh models list after download
                                        onRefreshModels()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
    
    // Enhanced Info Dialog
    showInfoDialog?.let { (modelInfo, downloadableModel) ->
    val listState = rememberLazyListState()
        val context = LocalContext.current
        // Fix [5.1]: describe storage without embedding /data/user/0/… paths
        val userFacingPath = remember(modelInfo.filePath, context) {
            val fileName = File(modelInfo.filePath).name
            val rel = File(context.filesDir, "models/$fileName")
            if (rel.absolutePath == modelInfo.filePath) {
                "App private storage: files/models/$fileName"
            } else {
                "Stored file: $fileName"
            }
        }
        val packageName = context.packageName
        AlertDialog(
            onDismissRequest = { showInfoDialog = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            title = { Text(modelInfo.name) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    downloadableModel?.let { dlModel ->
                        item("description") {
                            Text(dlModel.description, style = MaterialTheme.typography.bodyMedium)
                        }
                        item("capabilities_header") {
                            Divider()
                            Text("Capabilities:", style = MaterialTheme.typography.titleSmall)
                        }
                        item("capabilities") {
                            Text(dlModel.capabilitiesText, style = MaterialTheme.typography.bodySmall)
                        }
                        item("recommended_header") {
                            Divider()
                            Text("Recommended For:", style = MaterialTheme.typography.titleSmall)
                        }
                        item("recommended") {
                            Text(dlModel.recommendedFor, style = MaterialTheme.typography.bodySmall)
                        }
                        item("technical_header") {
                            Divider()
                            Text("Technical Details:", style = MaterialTheme.typography.titleSmall)
                        }
                        item("technical") {
                            Text("Creator: ${dlModel.creator}", style = MaterialTheme.typography.bodySmall)
                            Text("Quantization: ${dlModel.quantization}", style = MaterialTheme.typography.bodySmall)
                        }
                        item("download_url") {
                            Divider()
                            Text("Download URL:", style = MaterialTheme.typography.titleSmall)
                            // Fix [2.3]: shared ClickableLink composable
                            ClickableLink(url = dlModel.downloadUrl, linkText = dlModel.downloadUrl)
                        }
                    }
                    item("file_size") {
                        Text("File Size: ${formatFileSize(modelInfo.size)}", style = MaterialTheme.typography.bodySmall)
                    }
                    item("stored_at") {
                        Text(
                            text = "Stored at: $userFacingPath",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    item("access_note") {
                        Text(
                            text = "Note: App-private storage requires root or 'adb shell run-as $packageName' to access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ModelItem(
    model: ModelInfo,
    isLoaded: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isLoaded) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Loaded",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Size: ${formatFileSize(model.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isLoaded) {
                    Button(onClick = onLoad) {
                        Text("Load")
                    }
                }
                
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun EnhancedModelItem(
    model: ModelInfo,
    downloadableModel: com.quantlm.yaser.domain.model.DownloadableModel?,
    isLoaded: Boolean,
    isLoadingThisModel: Boolean = false,
    isAnyModelOperationInProgress: Boolean = false,
    mmprojDownloadState: DownloadState? = null,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onShowInfo: () -> Unit,
    onDownloadVision: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isLoadingThisModel -> MaterialTheme.colorScheme.tertiaryContainer
                isLoaded -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isLoaded) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Loaded",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    downloadableModel?.let {
                        Text(
                            text = it.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                IconButton(onClick = onShowInfo) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Model Info",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Show mmproj download progress if downloading
            when (mmprojDownloadState) {
                is DownloadState.Downloading -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Downloading vision support...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${(mmprojDownloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = mmprojDownloadState.progress,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                is DownloadState.Error -> {
                    Text(
                        text = "❌ Vision download failed: ${mmprojDownloadState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                is DownloadState.Success -> {
                    Text(
                        text = "✓ Vision support downloaded! Reload model to enable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                else -> {}
            }
            
            // Model metadata chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vision badge - show prominently if model supports vision
                if (model.isVisionModel) {
                    AssistChip(
                        onClick = { },
                        label = { Text("📷 Vision", style = MaterialTheme.typography.labelSmall) },
                        enabled = false,
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                } else if (model.isVisionIncomplete && mmprojDownloadState !is DownloadState.Downloading) {
                    // Vision incomplete - mmproj not downloaded (only show if not already downloading)
                    AssistChip(
                        onClick = onDownloadVision,
                        label = { Text("⚠️ Vision Incomplete", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
                downloadableModel?.let { dlModel ->
                    AssistChip(
                        onClick = { },
                        label = { Text(dlModel.creator, style = MaterialTheme.typography.labelSmall) },
                        enabled = false
                    )
                    AssistChip(
                        onClick = { },
                        label = { Text(dlModel.quantization, style = MaterialTheme.typography.labelSmall) },
                        enabled = false
                    )
                }
                AssistChip(
                    onClick = { },
                    label = { Text(formatFileSize(model.size), style = MaterialTheme.typography.labelSmall) },
                    enabled = false
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLoaded && isLoadingThisModel) {
                    Button(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unloading...")
                    }
                } else if (isLoaded) {
                    Button(
                        onClick = onUnload,
                        modifier = Modifier.weight(1f),
                        enabled = !isAnyModelOperationInProgress,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Unload Model")
                    }
                } else if (isLoadingThisModel) {
                    // Show loading button for this specific model
                    Button(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading...")
                    }
                } else {
                    Button(
                        onClick = onLoad,
                        modifier = Modifier.weight(1f),
                        enabled = !isAnyModelOperationInProgress
                    ) {
                        Text("Load Model")
                    }
                }
                
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !isLoaded && !isAnyModelOperationInProgress
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

internal fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    
    return when {
        gb >= 1 -> "%.2f GB".format(gb)
        mb >= 1 -> "%.2f MB".format(mb)
        else -> "%.2f KB".format(kb)
    }
}
