package com.sherwood.wta.core.startup

import android.content.Context
import com.sherwood.wta.core.crypto.SecurityInitializer
import com.sherwood.wta.core.logging.AppLogger

class SecurityStartup(private val context: Context) {

    fun initialize() {
        try {
            val initialized = SecurityInitializer.initialize(context) { result ->
                AppLogger.w(
                    "SherwoodStudioApplication",
                    "Security threat detected: level=${result.threatLevel}, block=${result.shouldBlock}, threats=${result.threats}"
                )
            }
            AppLogger.i("SherwoodStudioApplication", "Security initializer status: $initialized")
        } catch (e: Exception) {
            AppLogger.e("SherwoodStudioApplication", "Security initialization failed", e)
        } catch (e: Error) {
            AppLogger.e("SherwoodStudioApplication", "Security initialization critical error", Error(e))
        }
    }

    fun shutdown() {
        SecurityInitializer.shutdown()
    }
}
