package com.quantlm.yaser.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Collapsible reasoning block rendered above the assistant response bubble.
 *
 * - While thinking is in progress (partial=true): shows "Thinking…" header, auto-expanded.
 * - Once complete: header shows the [thoughtSummary] sentence (if provided) or "Show thinking".
 * - Expanded body: full chain-of-thought text with a 2dp left accent line.
 * - No card/bubble surface — sits directly on the chat background to keep it visually
 *   distinct from the reply bubble beneath it.
 */
@Composable
fun MessageBodyThinking(
    text: String,
    inProgress: Boolean,
    modifier: Modifier = Modifier,
    thoughtSummary: String? = null,
) {
    // Collapsed by default; auto-expands while thinking is streaming and
    // collapses again once the block completes (see reference screenshots).
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(inProgress) {
        expanded = inProgress
    }

    val accentColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

    Column(modifier = modifier.fillMaxWidth()) {
        // Header row — tap to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onTap = { expanded = !expanded }) }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = accentColor,
            )
            Text(
                text = when {
                    inProgress -> "Thinking…"
                    !thoughtSummary.isNullOrBlank() -> thoughtSummary
                    else -> "Show thinking"
                },
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = if (inProgress) accentColor else labelColor,
            )
        }

        // Expandable body with left accent line
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val strokeWidth = 2.dp.toPx()
                        drawRect(
                            brush = SolidColor(accentColor),
                            topLeft = Offset(0f, 0f),
                            size = Size(strokeWidth, size.height),
                        )
                    }
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
            ) {
                Text(
                    text = text.ifBlank { "…" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = bodyColor,
                )
            }
        }
    }
}
