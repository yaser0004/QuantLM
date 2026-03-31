package com.quantlm.yaser.domain.model

sealed class DownloadState {
    object Idle : DownloadState()
    /** Model is waiting to be downloaded — another download is already running. */
    object Queued : DownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSecond: Long = 0L,
        val etaSeconds: Long = -1L // -1 means unknown
    ) : DownloadState()
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    object Success : DownloadState()
    data class Error(val message: String) : DownloadState()
    object Cancelled : DownloadState()
}

/**
 * Helper functions for formatting download stats
 */
object DownloadFormatter {
    fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> String.format("%.0f KB/s", bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }
    
    fun formatEta(seconds: Long): String {
        return when {
            seconds < 0 -> "Calculating..."
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
    
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
