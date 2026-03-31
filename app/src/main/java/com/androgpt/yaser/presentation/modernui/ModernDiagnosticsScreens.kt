package com.quantlm.yaser.presentation.modernui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.quantlm.yaser.data.diagnostics.SystemLogDiagnostics
import com.quantlm.yaser.presentation.models.ModelsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val LOG_REFRESH_INTERVAL_MS = 4_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSystemLogsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val header = withContext(Dispatchers.IO) {
                SystemLogDiagnostics.buildHeaderLines(context.applicationContext)
            }
            val (logLines, hintMsg) = withContext(Dispatchers.IO) {
                SystemLogDiagnostics.readFilteredLogcat(context.applicationContext)
            }
            lines = buildList {
                addAll(header)
                add("")
                addAll(logLines)
            }
            hint = hintMsg
            ready = true
            delay(LOG_REFRESH_INTERVAL_MS)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("System logs")
                        IconButton(
                            onClick = {
                                val text = lines.joinToString("\n")
                                if (text.isBlank()) {
                                    Toast.makeText(context, "Nothing to copy yet", Toast.LENGTH_SHORT).show()
                                } else {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("QuantLM logs", text))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy logs"
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { padding ->
        if (!ready) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    hint?.let {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = it,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = "Refreshes about every ${LOG_REFRESH_INTERVAL_MS / 1000}s while this screen is open. Includes persistent app events for model loading, downloads, chat/generation, and settings updates.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                itemsIndexed(lines, key = { ix, _ -> ix }) { _, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernModelBenchmarkScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    modelsViewModel: ModelsViewModel = hiltViewModel()
) {
    val loadedModel by modelsViewModel.loadedModel.collectAsState()
    val modelLoadingState by modelsViewModel.modelLoadingState.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Model benchmark") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active model",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = loadedModel?.name ?: "None loaded",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    loadedModel?.let { m ->
                        Text(
                            text = m.filePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = "After each assistant reply, open the message actions and view generation stats for tokens, sampling settings, and memory. On the Models tab, use the download screen’s progress rows to monitor large transfers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Loader state: $modelLoadingState",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
