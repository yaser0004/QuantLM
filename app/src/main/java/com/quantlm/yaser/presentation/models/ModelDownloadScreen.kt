package com.quantlm.yaser.presentation.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.quantlm.yaser.presentation.ui.common.ClickableLink
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.quantlm.yaser.domain.model.DownloadFormatter
import com.quantlm.yaser.domain.model.DownloadState
import com.quantlm.yaser.domain.model.DownloadableModel

@Composable
fun ModelDownloadScreen(
    viewModel: ModelDownloadViewModel = hiltViewModel(),
    onModelDownloaded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val completionMessage by viewModel.completionMessage.collectAsState()
    val modelSizes by viewModel.modelSizes.collectAsState()
    val fetchingSizes by viewModel.fetchingSizes.collectAsState()
    
    var showDeleteDialog by remember { mutableStateOf<DownloadableModel?>(null) }
    var expandedSections by remember { mutableStateOf(setOf<String>()) }

    // Group models by explicit section title (for example, "Qwen by Alibaba")
    val groupedModels = remember(availableModels) {
        availableModels.groupBy { it.getSectionTitle() }
            .toSortedMap()
    }
    
    // Refresh downloaded models when screen appears
    LaunchedEffect(Unit) {
        viewModel.refreshDownloadedModels()
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Available Models",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Download language models to run locally on your device. Choose based on your device capacity and needs:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 Tap the info icon (ⓘ) next to each model for detailed capabilities and recommendations",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // Grouped models by section title
            groupedModels.forEach { (sectionTitle, models) ->
                item(key = "header_$sectionTitle") {
                    CreatorGroupHeader(
                        title = sectionTitle,
                        modelCount = models.size,
                        isExpanded = expandedSections.contains(sectionTitle),
                        onToggle = {
                            expandedSections = if (expandedSections.contains(sectionTitle)) {
                                expandedSections - sectionTitle
                            } else {
                                expandedSections + sectionTitle
                            }
                        }
                    )
                }
                
                if (expandedSections.contains(sectionTitle)) {
                    items(models, key = { it.id }) { model ->
                        val fetchedSizes = modelSizes[model.id]
                        val isFetchingSize = fetchingSizes.contains(model.id)
                        
                        DownloadableModelItem(
                            model = model,
                            downloadState = downloadStates[model.id] ?: DownloadState.Idle,
                            isDownloaded = downloadedModels.contains(model.id),
                            fetchedMainSize = fetchedSizes?.first ?: 0L,
                            fetchedMmprojSize = fetchedSizes?.second ?: 0L,
                            isFetchingSize = isFetchingSize,
                            onDownload = { viewModel.downloadModel(model, onSuccess = onModelDownloaded) },
                            onCancel = { viewModel.cancelDownload(model) },
                            onDelete = { showDeleteDialog = model },
                            onClearError = { viewModel.clearDownloadState(model) },
                            onFetchSize = { viewModel.fetchModelSize(model.id) }
                        )
                    }
                }
            }
        }
        
        // Show completion message as a snackbar at the bottom
        completionMessage?.let { message ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.dismissCompletionMessage() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
    
    // Delete confirmation dialog
    showDeleteDialog?.let { model ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = { Text("Are you sure you want to delete ${model.name}? You'll need to download it again to use it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(model)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
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
}

@Composable
fun CreatorGroupHeader(
    title: String,
    modelCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "$modelCount ${if (modelCount == 1) "model" else "models"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                imageVector = if (isExpanded) 
                    Icons.Default.KeyboardArrowUp 
                else 
                    Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
fun DownloadableModelItem(
    model: DownloadableModel,
    downloadState: DownloadState,
    isDownloaded: Boolean,
    fetchedMainSize: Long = 0L,
    fetchedMmprojSize: Long = 0L,
    isFetchingSize: Boolean = false,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onClearError: () -> Unit,
    onFetchSize: () -> Unit = {}
) {
    var showModelInfo by remember { mutableStateOf(false) }
    
    // Determine effective sizes - prefer fetched, then defined
    val effectiveMainSize = when {
        fetchedMainSize > 0 -> fetchedMainSize
        model.fileSize > 0 -> model.fileSize
        else -> 0L
    }
    val effectiveMmprojSize = when {
        fetchedMmprojSize > 0 -> fetchedMmprojSize
        model.mmprojFileSize > 0 -> model.mmprojFileSize
        else -> 0L
    }
    val totalSize = effectiveMainSize + effectiveMmprojSize
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDownloaded -> MaterialTheme.colorScheme.primaryContainer
                downloadState is DownloadState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Title row with info button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDownloaded) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { showModelInfo = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Model Info",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Description and size
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            // Size display with loading indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when {
                        totalSize > 0 -> "Size: ${formatFileSize(totalSize)}"
                        isFetchingSize -> "Size: Checking..."
                        else -> "Size: Tap to check"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (totalSize == 0L && !isFetchingSize) {
                        Modifier.clickable { onFetchSize() }
                    } else Modifier
                )
                if (isFetchingSize) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (downloadState) {
                    is DownloadState.Idle -> {
                        if (isDownloaded) {
                            Button(
                                onClick = onDelete,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Delete")
                            }
                        } else {
                            Button(onClick = onDownload) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download")
                            }
                        }
                    }
                    is DownloadState.Downloading -> {
                        OutlinedButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel Download")
                        }
                    }
                    is DownloadState.Success -> {
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                    is DownloadState.Error -> {
                        Button(onClick = onClearError) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                    is DownloadState.Cancelled -> {
                        Button(onClick = onDownload) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                    else -> {}
                }
            }
            
            // Download progress
            when (downloadState) {
                is DownloadState.Downloading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column {
                        LinearProgressIndicator(
                            progress = downloadState.progress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${formatFileSize(downloadState.downloadedBytes)} / ${formatFileSize(downloadState.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Speed and ETA row
                        if (downloadState.speedBytesPerSecond > 0 || downloadState.etaSeconds >= 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (downloadState.speedBytesPerSecond > 0) 
                                        DownloadFormatter.formatSpeed(downloadState.speedBytesPerSecond) 
                                    else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (downloadState.etaSeconds >= 0) 
                                        "${DownloadFormatter.formatEta(downloadState.etaSeconds)} remaining" 
                                    else "Calculating...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                is DownloadState.Paused -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column {
                        val progress = downloadState.downloadedBytes.toFloat() / downloadState.totalBytes.toFloat()
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Paused at ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            Text(
                                text = "${formatFileSize(downloadState.downloadedBytes)} / ${formatFileSize(downloadState.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is DownloadState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Error: ${downloadState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }
        }
    }
    
    // Model Info Dialog
    if (showModelInfo) {
        val listState = rememberLazyListState()
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { showModelInfo = false },
            title = { Text(model.name) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item("description") {
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (model.capabilities.isNotEmpty()) {
                        item("capabilities") {
                            Divider()
                            Text(
                                text = "Capabilities:",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = model.capabilities,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (model.recommendedFor.isNotEmpty()) {
                        item("recommended") {
                            Divider()
                            Text(
                                text = model.recommendedFor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    item("file_size") {
                        Divider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = when {
                                    totalSize > 0 -> "File Size: ${formatFileSize(totalSize)}"
                                    isFetchingSize -> "File Size: Checking..."
                                    else -> "File Size: Tap to check"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = if (totalSize == 0L && !isFetchingSize) {
                                    Modifier.clickable { onFetchSize() }
                                } else Modifier
                            )
                            if (isFetchingSize) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Text(
                            text = "Quantization: ${model.quantization}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item("download_url") {
                        Divider()
                        Text(
                            text = "Download URL:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        ClickableLink(url = model.downloadUrl, linkText = model.downloadUrl)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { uriHandler.openUri(model.downloadUrl) }) {
                    Text("Open Link")
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelInfo = false }) {
                    Text("Close")
                }
            }
        )
    }
}
