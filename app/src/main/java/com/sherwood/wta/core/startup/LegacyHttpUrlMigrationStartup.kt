package com.sherwood.wta.core.startup

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.data.repository.WebAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class LegacyHttpUrlMigrationStartup(
    private val context: Context,
    private val webAppRepository: WebAppRepository,
) {

    fun initialize(appScope: CoroutineScope) {

    }

    private companion object {
        private const val PREFS_NAME = "security_migrations"
        private const val KEY_HTTP_URL_MIGRATED = "legacy_http_url_migrated_v1"
    }
}
