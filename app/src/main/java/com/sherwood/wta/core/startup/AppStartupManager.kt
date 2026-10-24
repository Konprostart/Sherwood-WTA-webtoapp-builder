package com.sherwood.wta.core.startup

import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.port.PortManager
import com.sherwood.wta.core.shell.ShellRuntimeServices
import com.sherwood.wta.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AppStartupManager(
    private val loggingStartup: LoggingStartup,
    private val shellRuntimeStartup: ShellRuntimeStartup,
    private val securityStartup: SecurityStartup,
    private val runtimeWarmupStartup: RuntimeWarmupStartup,
    private val legacyHttpUrlMigrationStartup: LegacyHttpUrlMigrationStartup,
    private val backgroundServicesStartup: BackgroundServicesStartup,
    private val database: AppDatabase,
) {

    fun initialize(appScope: CoroutineScope) {
        loggingStartup.initialize()
        shellRuntimeStartup.initialize()

        appScope.launch(Dispatchers.Default) {
            securityStartup.initialize()
        }
        legacyHttpUrlMigrationStartup.initialize(appScope)
        runtimeWarmupStartup.initialize(appScope)
        backgroundServicesStartup.initialize(appScope)
        AppLogger.system("Application", "onCreate completed")
    }

    fun clearCaches() {
        try {
            com.sherwood.wta.core.crypto.AesCryptoEngine.clearKeyCache()
            com.sherwood.wta.util.HtmlProjectProcessor.clearEncodingCache()
            AppLogger.i("WebToAppApplication", "App caches cleared")
        } catch (e: Exception) {
            AppLogger.e("WebToAppApplication", "Failed to clear app caches", e)
        }
    }

    fun shutdown(appScope: CoroutineScope) {
        AppLogger.system("Application", "onTerminate started")

        appScope.coroutineContext[Job]?.cancel()
        backgroundServicesStartup.shutdown()
        PortManager.releaseAll()
        com.sherwood.wta.util.OfflineManager.release()
        com.sherwood.wta.core.plugin.PluginStore.release()
        com.sherwood.wta.util.DownloadNotificationManager.release()
        com.sherwood.wta.core.crypto.AesCryptoEngine.clearKeyCache()
        runtimeWarmupStartup.shutdown()
        securityStartup.shutdown()
        database.close()
        ShellRuntimeServices.reset()
        loggingStartup.shutdown()
    }
}
