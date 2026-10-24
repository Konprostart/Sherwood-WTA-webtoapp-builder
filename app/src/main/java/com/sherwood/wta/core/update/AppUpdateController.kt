package com.sherwood.wta.core.update

import com.sherwood.wta.core.logging.AppLogger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches and checks the update manifest of a generated app. It does not download or install
 * the APK; that step belongs to the caller after the user confirms.
 */
object AppUpdateController {

    private const val TAG = "AppUpdateController"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    /**
     * Runs one check. Network errors and oversized responses are reported as
     * [AppUpdateManifest.Verdict.Rejected], never thrown.
     */
    suspend fun check(config: AppUpdateConfig, installedVersionCode: Long): AppUpdateManifest.Verdict =
        withContext(Dispatchers.IO) {
            if (!config.isUsable) {
                return@withContext AppUpdateManifest.Verdict.Rejected("auto-update is not configured")
            }
            val body = try {
                fetchManifest(config.manifestUrl)
            } catch (e: IOException) {
                AppLogger.w(TAG, "Manifest fetch failed: ${e.message}")
                return@withContext AppUpdateManifest.Verdict.Rejected("manifest could not be fetched")
            }
            AppUpdateManifest.evaluate(body, config.publicKeyBase64, installedVersionCode)
        }

    private fun fetchManifest(url: String): String {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) { "manifest URL must use https" }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(MAX_MANIFEST_BYTES + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                if (total > MAX_MANIFEST_BYTES) throw IOException("manifest is too large")
                buffer.copyOf(total)
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
