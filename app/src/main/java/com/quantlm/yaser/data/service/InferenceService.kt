package com.quantlm.yaser.data.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*

/**
 * Foreground service to keep inference running when app is in background.
 * This prevents the system from killing the process during long-running generation.
 */
@AndroidEntryPoint
class InferenceService : Service() {
    
    private val binder = InferenceBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var isGenerating = false
    
    companion object {
        private const val TAG = "InferenceService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "inference_channel"
        private const val COMPLETION_NOTIFICATION_ID = 2002
        private const val COMPLETION_CHANNEL_ID = "inference_completion_channel"
        private const val MAX_COMPLETION_NOTIFICATION_TEXT_LENGTH = 3500
        
        const val ACTION_START_INFERENCE = "com.quantlm.yaser.START_INFERENCE"
        const val ACTION_STOP_INFERENCE = "com.quantlm.yaser.STOP_INFERENCE"
        
        /**
         * Start inference service to keep CPU awake during generation
         */
        fun startInference(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_START_INFERENCE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop inference service when generation is complete
         */
        fun stopInference(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_STOP_INFERENCE
            }
            context.startService(intent)
        }

        fun showCompletionNotification(context: Context, responseText: String) {
            val normalizedText = responseText.trim()
            if (normalizedText.isEmpty()) return

            if (!canPostNotifications(context)) {
                Log.w(TAG, "Completion notification skipped: notifications are disabled")
                return
            }

            createCompletionNotificationChannel(context)

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

            val contentIntent = launchIntent?.let {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                PendingIntent.getActivity(context, 0, it, flags)
            }

            val preview = normalizedText.take(MAX_COMPLETION_NOTIFICATION_TEXT_LENGTH)
            val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle("Response generated")
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .apply {
                    contentIntent?.let { setContentIntent(it) }
                }
                .build()

            NotificationManagerCompat.from(context).notify(COMPLETION_NOTIFICATION_ID, notification)
        }

        private fun createCompletionNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Response Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows when an AI response is ready"
                setShowBadge(true)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        private fun canPostNotifications(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                return false
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            return true
        }
    }
    
    inner class InferenceBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Inference service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_INFERENCE -> {
                if (!isGenerating) {
                    startForegroundInference()
                }
            }
            ACTION_STOP_INFERENCE -> {
                stopForegroundInference()
            }
        }
        
        return START_STICKY
    }
    
    private fun startForegroundInference() {
        isGenerating = true
        acquireWakeLock()
        
        val notification = createNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            Log.i(TAG, "Inference service started in foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }
    
    private fun stopForegroundInference() {
        isGenerating = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Inference service stopped")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when AI is processing your request"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuantLM")
            .setContentText("Processing your request...")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "QuantLM:InferenceWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.let {
            if (!it.isHeld) {
                // Max 30 minutes timeout for long responses
                it.acquire(30 * 60 * 1000L)
                Log.d(TAG, "Wake lock acquired for inference")
            }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Inference service destroyed")
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
