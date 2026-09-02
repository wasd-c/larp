package com.anis.larp

import android.app.Application
import com.anis.larp.telemetry.Telemetry

class LarpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.initializeIfConsented(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Telemetry.event(
            name = "app_memory_pressure",
            attributes = mapOf("level" to level)
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Telemetry.event("app_low_memory")
    }
}
