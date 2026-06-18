package com.quantlm.yaser.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantlm.yaser.domain.model.DownloadableModel
import com.quantlm.yaser.domain.model.ModelCapability

/**
 * Generalized capability-aware model state, derived for each [ModelCapability]
 * the user can toggle from the chat composer. Mirrors the historical
 * [VisionModelState] sealed class but parameterized by capability so audio,
 * reasoning, speculative-decoding and agent-skills toggles can share the same
 * chooser bottom sheet.
 */
sealed class CapabilityModelState {
    /** Active model already supports this capability — nothing to switch. */
    object Ready : CapabilityModelState()

    /**
     * One or more downloaded models declare [capability] but none is currently
     * loaded. [preferred] is the suggested entry (typically smallest by size).
     */
    data class Downloaded(
        val capability: ModelCapability,
        val preferred: DownloadableModel,
        val allDownloaded: List<DownloadableModel>,
    ) : CapabilityModelState()

    /**
     * No downloaded model declares [capability]. [available] lists every
     * manifest entry that does; [recommended] is the suggested first download.
     */
    data class NotDownloaded(
        val capability: ModelCapability,
        val recommended: DownloadableModel,
        val available: List<DownloadableModel>,
    ) : CapabilityModelState()
}

/** Human-readable label shown in chooser header + composer menu rows. */
fun ModelCapability.displayLabel(): String = when (this) {
    ModelCapability.VISION -> "Vision"
    ModelCapability.AUDIO -> "Audio Scribe"
    ModelCapability.REASONING -> "Reasoning"
    ModelCapability.SPECULATIVE_DECODING -> "Speculative decoding"
    ModelCapability.AGENT_SKILLS -> "Agent skills"
    ModelCapability.DEVICE_ACTIONS -> "Device actions"
}

fun ModelCapability.displayIcon(): ImageVector = when (this) {
    ModelCapability.VISION -> Icons.Default.Image
    ModelCapability.AUDIO -> Icons.Default.AudioFile
    ModelCapability.REASONING -> Icons.Default.Psychology
    ModelCapability.SPECULATIVE_DECODING -> Icons.Default.Speed
    ModelCapability.AGENT_SKILLS -> Icons.Default.Build
    ModelCapability.DEVICE_ACTIONS -> Icons.Default.Build
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityModelChooserSheet(
    capability: ModelCapability,
    downloaded: List<DownloadableModel>,
    available: List<DownloadableModel>,
    onUse: (DownloadableModel) -> Unit,
    onDownload: (DownloadableModel) -> Unit,
    onDismiss: () -> Unit,
    activeModelFileName: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = capability.displayIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Choose a model that supports ${capability.displayLabel()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = "Pick from already-downloaded models or grab a new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (downloaded.isNotEmpty()) {
                    item {
                        SectionHeader("Downloaded")
                    }
                    items(downloaded, key = { it.id }) { model ->
                        val isActive = model.fileName == activeModelFileName
                        CapabilityModelRow(
                            model = model,
                            isDownloaded = true,
                            isActive = isActive,
                            onPrimaryAction = { if (!isActive) onUse(model) },
                        )
                    }
                }

                if (available.isNotEmpty()) {
                    item {
                        if (downloaded.isNotEmpty()) {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                        SectionHeader("Available to download")
                    }
                    items(available, key = { it.id }) { model ->
                        CapabilityModelRow(
                            model = model,
                            isDownloaded = false,
                            onPrimaryAction = { onDownload(model) },
                        )
                    }
                }

                if (downloaded.isEmpty() && available.isEmpty()) {
                    item {
                        Text(
                            text = "No models with ${capability.displayLabel()} are available right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun CapabilityModelRow(
    model: DownloadableModel,
    isDownloaded: Boolean,
    onPrimaryAction: () -> Unit,
    isActive: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append(humanReadableSize(model.fileSize))
                        if (model.capabilitiesText.isNotBlank()) {
                            val firstLine = model.capabilitiesText.lineSequence().firstOrNull()?.trim()
                                ?.removePrefix("• ")
                            if (!firstLine.isNullOrBlank()) {
                                append(" · ")
                                append(firstLine)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!model.loadable && model.unsupportedReason != null) {
                    Text(
                        text = model.unsupportedReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            when {
                !model.loadable -> {
                    // Crash guard: disable both Use and Download for models the
                    // bundled runtime cannot load. Leaving Use clickable would
                    // route into ModelRepositoryImpl.loadModel which now
                    // returns Result.failure, but presenting "Use" sets a false
                    // expectation. "Unavailable" makes the gap explicit.
                    OutlinedButton(onClick = {}, enabled = false) { Text("Unavailable") }
                }
                isActive -> Button(onClick = {}, enabled = false) { Text("Active") }
                isDownloaded -> Button(onClick = onPrimaryAction) { Text("Use") }
                else -> OutlinedButton(onClick = onPrimaryAction) { Text("Download") }
            }
        }
    }
}

private fun humanReadableSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return String.format("%.1f GB", gb)
    val mb = bytes / 1_048_576.0
    return String.format("%.0f MB", mb)
}
