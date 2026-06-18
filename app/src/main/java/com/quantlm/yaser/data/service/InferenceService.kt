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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*

/**
 * Foreground service that raises the process to foreground importance so the OS does not
 * kill it mid-generation. The CPU wake lock for inference is owned by
 * `InferenceRepositoryImpl` (scoped to a single generation); this service intentionally
 * holds no wake lock to avoid the prior pair of overlapping locks.
 */
@AndroidEntryPoint
class InferenceService : Service() {

    private val binder = InferenceBinder()
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
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // App backgrounded with the service already stopped (e.g. a
                // late duplicate stop after a long-stopped generation finally
                // wound down) — Android forbids the background start; there is
                // nothing left to stop.
                Log.w(TAG, "stopInference skipped: ${e.message}")
            }
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

        // NOT_STICKY: a system-restarted instance would arrive with a null
        // intent, never call startForeground, and idle as a zombie — the
        // generation it accompanied died with the process anyway.
        return START_NOT_STICKY
    }
    
    private fun startForegroundInference() {
        isGenerating = true

        val notification = createNotification()
        try {
            // SPECIAL_USE is the type Google guides for compute jobs that
            // don't fit a more-specific bucket (no sync, no media, no
            // location). Matches the SPECIAL_USE_FGS_SUBTYPE property in
            // AndroidManifest.xml. DATA_SYNC, used previously, gave the
            // scheduler the wrong hint and didn't help under contention.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Log.i(TAG, "Inference service started in foreground")
            AppEventLogger.info(component = TAG, action = "foreground_started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            AppEventLogger.error(component = TAG, action = "foreground_start_failed", throwable = e)
        }
    }

    private fun stopForegroundInference() {
        isGenerating = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Inference service stopped")
        AppEventLogger.info(component = TAG, action = "foreground_stopped")
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
    
    override fun onDestroy() {
        Log.d(TAG, "Inference service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }
}
