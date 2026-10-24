package com.sherwood.wta.core.startup

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger

class LoggingStartup(private val context: Context) {

    fun initialize() {
        try {
            AppLogger.init(context)
            AppLogger.system("Application", "onCreate started")
        } catch (e: Exception) {
            android.util.Log.e("WebToAppApplication", "AppLogger initialization failed", e)
        }
    }

    fun shutdown() {
        AppLogger.shutdown()
    }
}
