package com.sherwood.wta.core.startup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RuntimeWarmupStartup(
    private val appContext: android.content.Context,
) {

    fun initialize(appScope: CoroutineScope) {
        appScope.launch {
            com.sherwood.wta.core.perf.SystemPerfOptimizer.initSystem(appContext)
            com.sherwood.wta.core.perf.SystemPerfOptimizer.readaheadCriticalFiles(appContext)
            // NOTE: do NOT preload adblock filters here. The full compiled rule set can be huge
            // (many large filter lists) and loading it on every cold start exhausted the heap and
            // crashed the app (issue #356). Filters are loaded lazily on first actual use
            // (preview/runtime via AdBlocker.loadHostsRules / prepareRuntimeFilters).
        }
        // Warm the GitHub mirror probe so the first update check / module-market
        // load / runtime download reads a hot cache instead of paying the probe
        // round up front. Costs a few KB total (one ranged request per channel).
        appScope.launch {
            runCatching { com.sherwood.wta.core.network.CnMirrorProbe.probe() }
        }
    }

    fun shutdown() {
        com.sherwood.wta.core.webview.WebViewPool.release()
        com.sherwood.wta.core.perf.SystemPerfOptimizer.release()
    }
}
