package com.quantlm.yaser.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quantlm.yaser.data.audio.AudioInputManager
import com.quantlm.yaser.data.audio.RecordingState
import kotlinx.coroutines.delay

/** Audio clips longer than this are auto-stopped (audio-encoder input cap). */
private const val MAX_RECORDING_MS = 30_000L

/**
 * Audio Scribe recorder. Captures a real audio clip via [AudioInputManager]
 * (distinct from the speech-to-text mic), then hands the raw PCM path to
 * [onAttach] when the user stops. Recording starts as soon as the dialog opens.
 */
@Composable
fun AudioRecorderDialog(
    audioInputManager: AudioInputManager,
    onAttach: (pcmPath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val recordingState by audioInputManager.recordingState.collectAsState()
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var stopRequested by remember { mutableStateOf(false) }

    // Start capture on open. Reset first so a stale Complete/Error state from a
    // previous recording can't be mistaken for this session's result.
    LaunchedEffect(Unit) {
        audioInputManager.resetState()
        audioInputManager.startRecording()
    }

    // Elapsed-time ticker, with a hard cap.
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Recording) {
            while (recordingState is RecordingState.Recording) {
                delay(100)
                elapsedMs += 100
                if (elapsedMs >= MAX_RECORDING_MS && !stopRequested) {
                    stopRequested = true
                    audioInputManager.stopRecording()
                }
            }
        }
    }

    // Hand the finished clip back to the caller.
    LaunchedEffect(recordingState) {
        val state = recordingState
        if (state is RecordingState.Complete) {
            onAttach(state.filePath)
            onDismiss()
        }
    }

    val error = (recordingState as? RecordingState.Error)?.message

    AlertDialog(
        onDismissRequest = {
            audioInputManager.stopRecording()
            audioInputManager.resetState()
            onDismiss()
        },
        icon = { Icon(Icons.Default.Mic, contentDescription = null) },
        title = { Text("Recording audio") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "%.1f s".format(elapsedMs / 1000f),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "Tap Stop to attach the clip to your message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (error == null) {
                TextButton(
                    onClick = {
                        if (!stopRequested) {
                            stopRequested = true
                            audioInputManager.stopRecording()
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("Stop")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    audioInputManager.stopRecording()
                    audioInputManager.resetState()
                    onDismiss()
                },
            ) { Text("Cancel") }
        },
    )
}
