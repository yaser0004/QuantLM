package com.quantlm.yaser.presentation.appsettings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A 3x3 pattern lock grid similar to Android's pattern lock.
 * Returns the pattern as a comma-separated string of dot indices (0-8).
 */
@Composable
fun PatternLockView(
    modifier: Modifier = Modifier,
    gridSize: Dp = 280.dp,
    dotRadius: Dp = 12.dp,
    selectedDotRadius: Dp = 16.dp,
    lineWidth: Dp = 4.dp,
    dotColor: Color = MaterialTheme.colorScheme.outlineVariant,
    selectedDotColor: Color = MaterialTheme.colorScheme.primary,
    lineColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
    errorColor: Color = MaterialTheme.colorScheme.error,
    isError: Boolean = false,
    enabled: Boolean = true,
    onPatternComplete: (String) -> Unit,
    onPatternStart: () -> Unit = {}
) {
    val density = LocalDensity.current
    val gridSizePx = with(density) { gridSize.toPx() }
    val dotRadiusPx = with(density) { dotRadius.toPx() }
    val selectedDotRadiusPx = with(density) { selectedDotRadius.toPx() }
    val lineWidthPx = with(density) { lineWidth.toPx() }
    val hitRadius = with(density) { 40.dp.toPx() } // Larger hit area for easier selection
    
    // Calculate dot positions (3x3 grid)
    val spacing = gridSizePx / 4
    val dotPositions = remember(gridSizePx) {
        val positions = mutableListOf<Offset>()
        for (row in 0..2) {
            for (col in 0..2) {
                positions.add(
                    Offset(
                        x = spacing + col * spacing,
                        y = spacing + row * spacing
                    )
                )
            }
        }
        positions
    }
    
    var selectedDots by remember { mutableStateOf(listOf<Int>()) }
    var currentPosition by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    
    val actualLineColor = if (isError) errorColor else lineColor
    val actualSelectedColor = if (isError) errorColor else selectedDotColor
    
    Box(
        modifier = modifier.size(gridSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(gridSize)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Find if we started on a dot
                            val hitDot = findNearestDot(offset, dotPositions, hitRadius)
                            if (hitDot != null) {
                                isDragging = true
                                selectedDots = listOf(hitDot)
                                currentPosition = offset
                                onPatternStart()
                            }
                        },
                        onDrag = { change, _ ->
                            if (isDragging) {
                                currentPosition = change.position
                                
                                // Check if we're over a new dot
                                val hitDot = findNearestDot(change.position, dotPositions, hitRadius)
                                if (hitDot != null && hitDot !in selectedDots) {
                                    selectedDots = selectedDots + hitDot
                                }
                            }
                        },
                        onDragEnd = {
                            if (isDragging && selectedDots.isNotEmpty()) {
                                val pattern = selectedDots.joinToString(",")
                                onPatternComplete(pattern)
                            }
                            isDragging = false
                            currentPosition = null
                        },
                        onDragCancel = {
                            isDragging = false
                            currentPosition = null
                            selectedDots = emptyList()
                        }
                    )
                }
        ) {
            // Draw connecting lines between selected dots
            if (selectedDots.size > 1) {
                for (i in 0 until selectedDots.size - 1) {
                    val start = dotPositions[selectedDots[i]]
                    val end = dotPositions[selectedDots[i + 1]]
                    drawLine(
                        color = actualLineColor,
                        start = start,
                        end = end,
                        strokeWidth = lineWidthPx,
                        cap = StrokeCap.Round
                    )
                }
            }
            
            // Draw line to current touch position
            if (isDragging && selectedDots.isNotEmpty() && currentPosition != null) {
                val lastDot = dotPositions[selectedDots.last()]
                drawLine(
                    color = actualLineColor.copy(alpha = 0.5f),
                    start = lastDot,
                    end = currentPosition!!,
                    strokeWidth = lineWidthPx,
                    cap = StrokeCap.Round
                )
            }
            
            // Draw dots
            dotPositions.forEachIndexed { index, position ->
                val isSelected = index in selectedDots
                val radius = if (isSelected) selectedDotRadiusPx else dotRadiusPx
                val color = if (isSelected) actualSelectedColor else dotColor
                
                // Outer ring for selected dots
                if (isSelected) {
                    drawCircle(
                        color = actualSelectedColor.copy(alpha = 0.2f),
                        radius = radius * 2,
                        center = position
                    )
                }
                
                // Main dot
                drawCircle(
                    color = color,
                    radius = radius,
                    center = position
                )
                
                // Inner highlight for selected dots
                if (isSelected) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f),
                        radius = radius * 0.5f,
                        center = position
                    )
                }
            }
        }
    }
    
    // Reset pattern after showing error
    LaunchedEffect(isError) {
        if (isError) {
            kotlinx.coroutines.delay(500)
            selectedDots = emptyList()
        }
    }
}

private fun findNearestDot(
    position: Offset,
    dotPositions: List<Offset>,
    hitRadius: Float
): Int? {
    dotPositions.forEachIndexed { index, dotPos ->
        val distance = sqrt(
            (position.x - dotPos.x).pow(2) + (position.y - dotPos.y).pow(2)
        )
        if (distance <= hitRadius) {
            return index
        }
    }
    return null
}

/**
 * Simplified pattern display for showing pattern dots without interaction
 */
@Composable
fun PatternDisplay(
    pattern: String,
    modifier: Modifier = Modifier,
    gridSize: Dp = 100.dp,
    dotRadius: Dp = 6.dp,
    lineWidth: Dp = 2.dp
) {
    val density = LocalDensity.current
    val gridSizePx = with(density) { gridSize.toPx() }
    val dotRadiusPx = with(density) { dotRadius.toPx() }
    val lineWidthPx = with(density) { lineWidth.toPx() }
    
    val selectedDots = remember(pattern) {
        pattern.split(",").mapNotNull { it.toIntOrNull() }
    }
    
    val dotPositions = remember(gridSizePx) {
        val spacing = gridSizePx / 4
        val positions = mutableListOf<Offset>()
        for (row in 0..2) {
            for (col in 0..2) {
                positions.add(
                    Offset(
                        x = spacing + col * spacing,
                        y = spacing + row * spacing
                    )
                )
            }
        }
        positions
    }
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    
    Canvas(modifier = modifier.size(gridSize)) {
        // Draw lines
        if (selectedDots.size > 1) {
            for (i in 0 until selectedDots.size - 1) {
                val startIndex = selectedDots[i]
                val endIndex = selectedDots[i + 1]
                if (startIndex in dotPositions.indices && endIndex in dotPositions.indices) {
                    drawLine(
                        color = primaryColor.copy(alpha = 0.7f),
                        start = dotPositions[startIndex],
                        end = dotPositions[endIndex],
                        strokeWidth = lineWidthPx,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        
        // Draw dots
        dotPositions.forEachIndexed { index, position ->
            val isSelected = index in selectedDots
            drawCircle(
                color = if (isSelected) primaryColor else outlineColor,
                radius = if (isSelected) dotRadiusPx * 1.3f else dotRadiusPx,
                center = position
            )
        }
    }
}
