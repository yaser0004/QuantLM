package com.quantlm.yaser.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.quantlm.yaser.R
import com.quantlm.yaser.domain.model.DownloadFormatter
import com.quantlm.yaser.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager = 
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    companion object {
        private const val TAG = "DownloadNotificationManager"
        const val CHANNEL_ID = "model_download_channel"
        const val CHANNEL_NAME = "Model Downloads"
        const val CHANNEL_DESCRIPTION = "Notifications for model download progress"
        private const val BASE_NOTIFICATION_ID = 1000
    }
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }
    
    fun getNotificationId(modelId: String): Int {
        return BASE_NOTIFICATION_ID + modelId.hashCode()
    }
    
    fun createDownloadNotification(
        @Suppress("UNUSED_PARAMETER") modelId: String, // Kept for API consistency
        modelName: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long = 0L,
        etaSeconds: Long = -1L
    ): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val progressPercent = (progress * 100).toInt()
        val downloadedFormatted = DownloadFormatter.formatFileSize(downloadedBytes)
        val totalFormatted = DownloadFormatter.formatFileSize(totalBytes)
        
        // Build content text with speed and ETA
        val contentText = buildString {
            append("$progressPercent% - $downloadedFormatted / $totalFormatted")
            if (speedBytesPerSecond > 0) {
                append(" • ${DownloadFormatter.formatSpeed(speedBytesPerSecond)}")
            }
            if (etaSeconds >= 0) {
                append(" • ${DownloadFormatter.formatEta(etaSeconds)} left")
            }
        }
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }
    
    fun showDownloadNotification(
        modelId: String,
        modelName: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long = 0L,
        etaSeconds: Long = -1L
    ): Int {
        val notificationId = getNotificationId(modelId)
        val notification = createDownloadNotification(
            modelId,
            modelName,
            progress,
            downloadedBytes,
            totalBytes,
            speedBytesPerSecond,
            etaSeconds
        )
        notificationManager.notify(notificationId, notification)
        // Log only at major milestones to reduce log spam
        val progressPercent = (progress * 100).toInt()
        if (progressPercent % 10 == 0) {
            Log.d(TAG, "Download progress for $modelName: $progressPercent%")
        }
        return notificationId
    }
    
    fun showDownloadCompleteNotification(
        modelId: String,
        modelName: String
    ) {
        val notificationId = getNotificationId(modelId)
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("$modelName is ready to use")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(notificationId, notification)
        Log.i(TAG, "Showing download complete notification for $modelName")
    }
    
    fun showDownloadErrorNotification(
        modelId: String,
        modelName: String,
        error: String
    ) {
        val notificationId = getNotificationId(modelId)
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText("$modelName: $error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(notificationId, notification)
        Log.e(TAG, "Showing download error notification for $modelName: $error")
    }
    
    fun cancelNotification(modelId: String) {
        val notificationId = getNotificationId(modelId)
        notificationManager.cancel(notificationId)
        Log.d(TAG, "Cancelled notification for model ID: $modelId")
    }
}
