package com.quantlm.yaser

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class QuantLMApplication : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        AppEventLogger.init(this)
        AppEventLogger.info(
            component = "Application",
            action = "startup",
            details = "device=${Build.MANUFACTURER} ${Build.MODEL}, api=${Build.VERSION.SDK_INT}"
        )
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

