package com.quantlm.yaser

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration as ResConfiguration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.diagnostics.PerformanceSnapshotLogger
import com.quantlm.yaser.data.inference.EngineRegistry
import com.quantlm.yaser.data.local.GenerationPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class QuantLMApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var engineRegistry: EngineRegistry

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // App-scoped scope for long-lived background collectors (settings sync).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Resolve the per-session-log opt-out BEFORE init so the choice is honored
        // from the first log line. A one-time fast DataStore read at cold start.
        val prefs = GenerationPreferences(this)
        AppEventLogger.persistSessionLogs = runBlocking { prefs.getSettings().first().persistSessionLogs }
        AppEventLogger.init(this)
        // Keep the flag in sync if the user toggles it at runtime.
        appScope.launch {
            prefs.getSettings()
                .map { it.persistSessionLogs }
                .distinctUntilChanged()
                .collect { AppEventLogger.persistSessionLogs = it }
        }
        PerformanceSnapshotLogger.init(this)
        AppEventLogger.info(
            component = "Application",
            action = "startup",
            details = "device=${Build.MANUFACTURER} ${Build.MODEL}, api=${Build.VERSION.SDK_INT}, cores=${Runtime.getRuntime().availableProcessors()}"
        )

        // Process-level foreground / background transitions. Fires once per
        // foreground bring-up and once per backgrounding — perfect signal-to-
        // noise ratio for diagnosing "app went away and came back".
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        // Per-Activity lifecycle. Fires for every Activity in the app on every
        // transition. Inexpensive (a Bundle-pointer log per call) and tells us
        // exactly when MainActivity (and any future activity) is alive.
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)

        // Hardware thermal-state transitions. Driven by the OS thermal HAL —
        // only fires on actual state changes (NONE → LIGHT etc.), not periodically.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerThermalListener()
        }

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppEventLogger.info(
            component = "Application",
            action = "trim_memory",
            details = "level=$level (${trimLevelName(level)})"
        )
        // Under any meaningful memory pressure, drop engines that are not the active one
        // (inactive engines retain native Vulkan/GPU buffers that show up as Gfx in
        // dumpsys meminfo). Under critical pressure, drop everything — losing the model
        // beats getting killed.
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> engineRegistry.releaseInactive()

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> engineRegistry.releaseAll()
        }
    }

    override fun onConfigurationChanged(newConfig: ResConfiguration) {
        super.onConfigurationChanged(newConfig)
        AppEventLogger.info(
            component = "Application",
            action = "configuration_changed",
            details = "orientation=${newConfig.orientation}, nightMode=${newConfig.uiMode}, locale=${newConfig.locales[0]}"
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppEventLogger.warn(component = "Application", action = "low_memory")
    }

    override fun onTerminate() {
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
            unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
            unregisterThermalListener()
        } finally {
            super.onTerminate()
        }
    }

    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "UNKNOWN"
    }

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            AppEventLogger.info(component = "ProcessLifecycle", action = "foregrounded")
        }

        override fun onStop(owner: LifecycleOwner) {
            AppEventLogger.info(component = "ProcessLifecycle", action = "backgrounded")
        }
    }

    private val activityLifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            AppEventLogger.debug(
                component = "ActivityLifecycle",
                action = "created",
                details = "name=${activity.javaClass.simpleName}, hasState=${savedInstanceState != null}"
            )
        }

        override fun onActivityStarted(activity: Activity) {
            AppEventLogger.debug(
                component = "ActivityLifecycle",
                action = "started",
                details = "name=${activity.javaClass.simpleName}"
            )
        }

        override fun onActivityResumed(activity: Activity) {
            AppEventLogger.info(
                component = "ActivityLifecycle",
                action = "resumed",
                details = "name=${activity.javaClass.simpleName}"
            )
        }

        override fun onActivityPaused(activity: Activity) {
            AppEventLogger.info(
                component = "ActivityLifecycle",
                action = "paused",
                details = "name=${activity.javaClass.simpleName}"
            )
        }

        override fun onActivityStopped(activity: Activity) {
            AppEventLogger.debug(
                component = "ActivityLifecycle",
                action = "stopped",
                details = "name=${activity.javaClass.simpleName}"
            )
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            // Skip: fires too often (rotation + system-driven save), low diagnostic value.
        }

        override fun onActivityDestroyed(activity: Activity) {
            AppEventLogger.debug(
                component = "ActivityLifecycle",
                action = "destroyed",
                details = "name=${activity.javaClass.simpleName}, finishing=${activity.isFinishing}"
            )
        }
    }

    private fun registerThermalListener() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            AppEventLogger.info(
                component = "Thermal",
                action = "status_changed",
                details = "status=${thermalStatusName(status)} (raw=$status)"
            )
        }
        try {
            pm.addThermalStatusListener(listener)
            thermalListener = listener
        } catch (e: Throwable) {
            AppEventLogger.warn(
                component = "Thermal",
                action = "register_failed",
                details = "reason=${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        thermalListener?.let { runCatching { pm.removeThermalStatusListener(it) } }
        thermalListener = null
    }

    private fun thermalStatusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
