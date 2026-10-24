package com.sherwood.wta.core.startup

import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.stats.AppHealthMonitor
import com.sherwood.wta.data.repository.WebAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class BackgroundServicesStartup(
    private val webAppRepository: WebAppRepository,
    private val healthMonitor: AppHealthMonitor,
    @Suppress("unused") private val screenshotService: com.sherwood.wta.core.stats.WebsiteScreenshotService,
) {

    fun initialize(appScope: CoroutineScope) {
        appScope.launch {
            try {
                healthMonitor.startMonitoring(webAppRepository.httpWebApps)
                AppLogger.i("WebToAppApplication", "Health monitor started")
            } catch (e: Exception) {
                AppLogger.e("WebToAppApplication", "Background services start failed", e)
            }
        }
    }

    fun shutdown() {
        try {
            healthMonitor.destroy()
        } catch (_: Exception) {
        }
    }
}
