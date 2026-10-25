package com.sherwood.wta.core.update

import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.data.model.AppUpdateConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    sealed class DownloadOutcome {
        /** The APK was downloaded and its SHA-256 matches the manifest. */
        data class Ready(val file: File) : DownloadOutcome()

        /** The downloaded bytes do not match the signed SHA-256; the file was deleted. */
        data object IntegrityMismatch : DownloadOutcome()

        data class Failed(val reason: String) : DownloadOutcome()
    }

    /**
     * Downloads the APK named by a verified manifest into [target]. Writes to a `.part` file first
     * and renames only after the SHA-256 matches, so a partial or tampered file never gets installed.
     */
    suspend fun downloadApk(manifest: AppUpdateManifest.Manifest, target: File): DownloadOutcome =
        withContext(Dispatchers.IO) {
            if (!AppUpdateManifest.isHttps(manifest.apkUrl)) {
                return@withContext DownloadOutcome.Failed("apkUrl must use https")
            }
            val parent = target.parentFile ?: return@withContext DownloadOutcome.Failed("invalid target")
            parent.mkdirs()
            val partial = File(parent, target.name + ".part")
            try {
                val connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    // Follows same-scheme redirects only; HttpURLConnection never follows https -> http.
                    instanceFollowRedirects = true
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext DownloadOutcome.Failed("HTTP ${connection.responseCode}")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_APK_BYTES) throw IOException("apk is too large")
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                        partial.delete()
                        return@withContext DownloadOutcome.IntegrityMismatch
                    }
                    target.delete()
                    if (!partial.renameTo(target)) {
                        partial.delete()
                        return@withContext DownloadOutcome.Failed("could not store the update")
                    }
                    DownloadOutcome.Ready(target)
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                AppLogger.w(TAG, "APK download failed: ${e.message}")
                partial.delete()
                DownloadOutcome.Failed("download failed")
            }
        }

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
