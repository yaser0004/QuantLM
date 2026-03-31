package com.quantlm.yaser.data.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.quantlm.yaser.domain.model.MobileTools
import com.quantlm.yaser.domain.model.Tool
import com.quantlm.yaser.domain.model.ToolCall
import com.quantlm.yaser.domain.model.ToolExecutor
import com.quantlm.yaser.domain.model.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ToolExecutor that executes mobile action tools
 * using Android system APIs and intents.
 */
@Singleton
class ToolExecutorImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolExecutor {
    
    companion object {
        private const val TAG = "ToolExecutorImpl"
    }
    
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    override suspend fun execute(call: ToolCall): ToolResult {
        Log.d(TAG, "Executing tool: ${call.name} with args: ${call.arguments}")
        
        return try {
            when (call.name) {
                "set_flashlight" -> executeFlashlight(call)
                "set_brightness" -> setBrightness(call)
                "set_volume" -> setVolume(call)
                "make_call" -> makeCall(call)
                "send_sms" -> sendSms(call)
                "compose_email" -> composeEmail(call)
                "create_reminder" -> createReminder(call)
                "create_calendar_event" -> createCalendarEvent(call)
                "web_search" -> webSearch(call)
                "open_map" -> openMap(call)
                "open_url" -> openUrl(call)
                "play_music" -> playMusic(call)
                "take_photo" -> takePhoto(call)
                "set_timer" -> setTimer(call)
                "open_app" -> openApp(call)
                "get_weather" -> getWeather(call)
                else -> ToolResult(
                    toolName = call.name,
                    success = false,
                    error = "Unknown tool: ${call.name}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool ${call.name}", e)
            ToolResult(
                toolName = call.name,
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    override fun isAvailable(toolName: String): Boolean {
        return MobileTools.getToolByName(toolName) != null
    }
    
    override fun getAvailableTools(): List<Tool> {
        return MobileTools.getAllTools()
    }
    
    // ========== Tool Implementations ==========
    
    private fun executeFlashlight(call: ToolCall): ToolResult {
        val enabled = call.arguments["enabled"] as? Boolean ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'enabled' parameter"
        )
        
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return ToolResult(
                toolName = call.name,
                success = false,
                error = "No camera found"
            )
            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Flashlight turned ${if (enabled) "on" else "off"}"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to control flashlight: ${e.message}"
            )
        }
    }
    
    private fun setBrightness(call: ToolCall): ToolResult {
        val level = (call.arguments["level"] as? Number)?.toInt() ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'level' parameter"
        )
        
        // Fix [6.1]: WRITE_SETTINGS is special — direct user to system screen instead of silent failure
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolResult(
                    toolName = call.name,
                    success = false,
                    error = "Opening system settings so you can grant screen brightness permission. Please toggle it on for this app, then retry."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WRITE_SETTINGS screen", e)
                ToolResult(
                    toolName = call.name,
                    success = false,
                    error = "Brightness control needs permission in Settings → Apps → Special access → Modify system settings."
                )
            }
        }
        
        return try {
            val brightness = (level.coerceIn(0, 100) * 255) / 100
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Brightness set to $level%"
            )
        } catch (e: SecurityException) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Need permission to change brightness. Please grant modify system settings for this app."
            )
        }
    }
    
    private fun setVolume(call: ToolCall): ToolResult {
        val level = (call.arguments["level"] as? Number)?.toInt() ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'level' parameter"
        )
        val stream = call.arguments["stream"] as? String ?: "media"
        
        val streamType = when (stream.lowercase()) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val targetVolume = (level.coerceIn(0, 100) * maxVolume) / 100
            audioManager.setStreamVolume(streamType, targetVolume, AudioManager.FLAG_SHOW_UI)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "$stream volume set to $level%"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to set volume: ${e.message}"
            )
        }
    }
    
    private fun makeCall(call: ToolCall): ToolResult {
        val number = call.arguments["number"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'number' parameter"
        )
        
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Calling $number"
            )
        } catch (e: SecurityException) {
            // Fallback to dial intent (doesn't require permission)
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening dialer for $number (call permission not granted)"
            )
        }
    }
    
    private fun sendSms(call: ToolCall): ToolResult {
        val number = call.arguments["number"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'number' parameter"
        )
        val message = call.arguments["message"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'message' parameter"
        )
        
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening SMS composer to $number"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to open SMS: ${e.message}"
            )
        }
    }
    
    private fun composeEmail(call: ToolCall): ToolResult {
        val to = call.arguments["to"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'to' parameter"
        )
        val subject = call.arguments["subject"] as? String
        val body = call.arguments["body"] as? String
        
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$to")
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening email composer to $to"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to open email: ${e.message}"
            )
        }
    }
    
    private fun createReminder(call: ToolCall): ToolResult {
        val title = call.arguments["title"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'title' parameter"
        )
        val time = call.arguments["time"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'time' parameter"
        )
        
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Try to parse time
                parseTime(time)?.let { (hour, minute) ->
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                }
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Creating reminder: $title at $time"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to create reminder: ${e.message}"
            )
        }
    }
    
    private fun createCalendarEvent(call: ToolCall): ToolResult {
        val title = call.arguments["title"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'title' parameter"
        )
        val startTime = call.arguments["start_time"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'start_time' parameter"
        )
        val endTime = call.arguments["end_time"] as? String
        val location = call.arguments["location"] as? String
        val description = call.arguments["description"] as? String
        
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening calendar to create event: $title"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to create calendar event: ${e.message}"
            )
        }
    }
    
    private fun webSearch(call: ToolCall): ToolResult {
        val query = call.arguments["query"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'query' parameter"
        )
        
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Searching for: $query"
            )
        } catch (e: Exception) {
            // Fallback to browser
            val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Searching for: $query"
            )
        }
    }
    
    private fun openMap(call: ToolCall): ToolResult {
        val location = call.arguments["location"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'location' parameter"
        )
        val mode = call.arguments["mode"] as? String ?: "driving"
        
        val modeParam = when (mode.lowercase()) {
            "walking" -> "w"
            "transit" -> "r"
            "bicycling" -> "b"
            else -> "d"
        }
        
        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(location)}&mode=$modeParam")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening navigation to $location ($mode)"
            )
        } catch (e: Exception) {
            // Fallback to geo URI
            val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(location)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mapIntent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening map for $location"
            )
        }
    }
    
    private fun openUrl(call: ToolCall): ToolResult {
        val url = call.arguments["url"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'url' parameter"
        )
        
        return try {
            val uri = if (!url.startsWith("http")) "https://$url" else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening $uri"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to open URL: ${e.message}"
            )
        }
    }
    
    private fun playMusic(call: ToolCall): ToolResult {
        val query = call.arguments["query"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'query' parameter"
        )
        
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Playing music: $query"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to play music: ${e.message}"
            )
        }
    }
    
    private fun takePhoto(call: ToolCall): ToolResult {
        val camera = call.arguments["camera"] as? String ?: "back"
        
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (camera.lowercase() == "front") {
                    putExtra("android.intent.extras.CAMERA_FACING", 1) // Front camera
                    putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                }
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening camera (${camera} facing)"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to open camera: ${e.message}"
            )
        }
    }
    
    private fun setTimer(call: ToolCall): ToolResult {
        val duration = call.arguments["duration"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'duration' parameter"
        )
        val label = call.arguments["label"] as? String
        
        return try {
            val seconds = parseDuration(duration)
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Setting timer for $duration${label?.let { " ($it)" } ?: ""}"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to set timer: ${e.message}"
            )
        }
    }
    
    private fun openApp(call: ToolCall): ToolResult {
        val appName = call.arguments["app_name"] as? String ?: return ToolResult(
            toolName = call.name,
            success = false,
            error = "Missing 'app_name' parameter"
        )
        
        // Fix [3.4]: use launcher queryIntentActivities (works with <queries> MAIN/LAUNCHER) instead of getInstalledApplications
        return try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            val target = resolves.firstOrNull { ri ->
                pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString().equals(appName, ignoreCase = true)
            }
            if (target != null) {
                val launchIntent = pm.getLaunchIntentForPackage(target.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolResult(
                        toolName = call.name,
                        success = true,
                        result = "Opening $appName"
                    )
                } else {
                    ToolResult(
                        toolName = call.name,
                        success = false,
                        error = "Cannot launch $appName"
                    )
                }
            } else {
                ToolResult(
                    toolName = call.name,
                    success = false,
                    error = "App '$appName' not found among installed launchable apps"
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to open app: ${e.message}"
            )
        }
    }
    
    private fun getWeather(call: ToolCall): ToolResult {
        val location = call.arguments["location"] as? String
        
        return try {
            val query = location ?: "weather"
            val uri = Uri.parse("https://www.google.com/search?q=weather+${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                toolName = call.name,
                success = true,
                result = "Opening weather for ${location ?: "current location"}"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = call.name,
                success = false,
                error = "Failed to get weather: ${e.message}"
            )
        }
    }
    
    // ========== Helper Functions ==========
    
    private fun parseTime(time: String): Pair<Int, Int>? {
        // Try to parse common time formats
        val patterns = listOf(
            Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d{1,2})\\s*(am|pm)", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(time)
            if (matcher.find()) {
                var hour = matcher.group(1)?.toIntOrNull() ?: continue
                val minute = if (matcher.groupCount() >= 2) (matcher.group(2)?.toIntOrNull() ?: 0) else 0
                val ampm = if (matcher.groupCount() >= 3) matcher.group(3)?.lowercase() else null
                
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                
                return hour to minute
            }
        }
        
        return null
    }
    
    private fun parseDuration(duration: String): Int {
        var totalSeconds = 0
        val hourPattern = Pattern.compile("(\\d+)\\s*h(our)?s?", Pattern.CASE_INSENSITIVE)
        val minutePattern = Pattern.compile("(\\d+)\\s*m(in(ute)?)?s?", Pattern.CASE_INSENSITIVE)
        val secondPattern = Pattern.compile("(\\d+)\\s*s(ec(ond)?)?s?", Pattern.CASE_INSENSITIVE)
        
        hourPattern.matcher(duration).let { if (it.find()) totalSeconds += (it.group(1)?.toIntOrNull() ?: 0) * 3600 }
        minutePattern.matcher(duration).let { if (it.find()) totalSeconds += (it.group(1)?.toIntOrNull() ?: 0) * 60 }
        secondPattern.matcher(duration).let { if (it.find()) totalSeconds += (it.group(1)?.toIntOrNull() ?: 0) }
        
        // If no pattern matched, try plain number as minutes
        if (totalSeconds == 0) {
            duration.filter { it.isDigit() }.toIntOrNull()?.let { totalSeconds = it * 60 }
        }
        
        return totalSeconds.coerceAtLeast(60) // Minimum 1 minute
    }
}
