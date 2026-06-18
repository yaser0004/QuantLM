package com.quantlm.yaser.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp

/**
 * Phase 2 (§3.8 / §4.1): audio source picker shown when the user taps the
 * "Audio Scribe" entry in the composer `+` menu on a model that supports
 * [com.quantlm.yaser.domain.model.ModelCapability.AUDIO]. The picker forks
 * between live recording (existing mic flow) and selecting an audio file.
 */
@Composable
fun AudioSourceDialog(
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onPickFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Scribe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Provide audio for the model to transcribe or reason about.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Record now") },
                    leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRecord() },
                )
                ListItem(
                    headlineContent = { Text("Pick a file") },
                    leadingContent = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickFile() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
