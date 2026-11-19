package com.sherwood.wta.core.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted memory of the last update check.
 *
 * The release API is remote and rate-limited, and the About screen is recomposed on every
 * navigation, so the answer is cached here instead of being re-fetched: a screen that opens
 * shows the previous verdict instantly, a silent check is allowed to run at most once a day,
 * and a manual check within [UpdateCheckPolicy.MANUAL_CHECK_FLOOR_MS] renders the cache rather
 * than spending another request.
 *
 * Only successful verdicts are stored. A failed check keeps the previous answer (it is not a
 * statement about the release) and only moves the attempt clock, so a flaky network cannot
 * hide a known update.
 *
 * Everything here is host-only data: a version string, the download URL, its size, an optional
 * SHA-256, and the release notes. No credentials, no tokens, no device identifiers.
 */
class UpdateCheckStore internal constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * The cached view of the release channel. [latestVersion] and [upToDateVersion] are mutually
     * exclusive: the last successful answer is one or the other.
     */
    data class Snapshot(
        val lastAttemptAtMillis: Long = 0L,
        val lastSuccessAtMillis: Long = 0L,
        val latestVersion: String? = null,
        val downloadUrl: String? = null,
        val sizeBytes: Long = 0L,
        val sha256: String? = null,
        val releaseNotes: String = "",
        val upToDateVersion: String? = null,
        val dismissedVersion: String? = null,
        val autoCheckEnabled: Boolean = true
    ) {

        /**
         * The cached update offer for an installed [currentVersionName], or null when the cache
         * holds no newer release (never checked, up to date, or the app was updated since).
         */
        fun availableUpdate(currentVersionName: String): UpdateChecker.ReleaseInfo? {
            val installed = UpdateChecker.Version.parse(currentVersionName) ?: return null
            val cached = latestVersion?.let { UpdateChecker.Version.parse(it) } ?: return null
            if (cached <= installed) return null
            val url = downloadUrl?.takeIf { it.isNotBlank() } ?: return null
            return UpdateChecker.ReleaseInfo(
                version = cached.toString(),
                downloadUrl = url,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                releaseNotes = releaseNotes
            )
        }

        /**
         * True when the version pill should carry the "update available" marker. Dismissing the
         * dialog hides the marker for that exact version, not for every future one.
         */
        fun showsUpdateIndicator(currentVersionName: String): Boolean {
            val info = availableUpdate(currentVersionName) ?: return false
            return info.version != dismissedVersion
        }
    }

    fun snapshot(): Snapshot = _state.value

    /** Records that a request was attempted, successful or not. */
    @Synchronized
    fun recordAttempt(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, nowMillis).apply()
        publish()
    }

    /** Records a successful verdict and drops the fields the other verdict owned. */
    @Synchronized
    fun recordResult(result: UpdateChecker.Result, nowMillis: Long) {
        val editor = prefs.edit().putLong(KEY_LAST_SUCCESS, nowMillis)
        when (result) {
            is UpdateChecker.Result.UpdateAvailable -> editor
                .putString(KEY_LATEST_VERSION, result.info.version)
                .putString(KEY_DOWNLOAD_URL, result.info.downloadUrl)
                .putLong(KEY_SIZE_BYTES, result.info.sizeBytes)
                .putString(KEY_SHA256, result.info.sha256)
                .putString(KEY_RELEASE_NOTES, result.info.releaseNotes)
                .remove(KEY_UP_TO_DATE_VERSION)

            is UpdateChecker.Result.UpToDate -> editor
                .putString(KEY_UP_TO_DATE_VERSION, result.version)
                .remove(KEY_LATEST_VERSION)
                .remove(KEY_DOWNLOAD_URL)
                .remove(KEY_SIZE_BYTES)
                .remove(KEY_SHA256)
                .remove(KEY_RELEASE_NOTES)

            is UpdateChecker.Result.Failed -> return
        }
        editor.apply()
        publish()
    }

    /** Hides the indicator for [version] without forgetting the release itself. */
    @Synchronized
    fun dismiss(version: String?) {
        prefs.edit().putString(KEY_DISMISSED_VERSION, version).apply()
        publish()
    }

    @Synchronized
    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        publish()
    }

    /**
     * The cached verdict for an installed version, or null when the cache cannot answer.
     * A cached "up to date" that is older than the installed version is not an answer.
     */
    fun cachedResult(currentVersionName: String): UpdateChecker.Result? {
        val snapshot = _state.value
        snapshot.availableUpdate(currentVersionName)?.let { info ->
            return UpdateChecker.Result.UpdateAvailable(info, currentVersionName)
        }
        val installed = UpdateChecker.Version.parse(currentVersionName)
        val reported = snapshot.upToDateVersion?.let { UpdateChecker.Version.parse(it) }
        if (installed != null && reported != null && reported >= installed) {
            return UpdateChecker.Result.UpToDate(reported.toString())
        }
        return null
    }

    private fun read(): Snapshot = Snapshot(
        lastAttemptAtMillis = prefs.getLong(KEY_LAST_ATTEMPT, 0L),
        lastSuccessAtMillis = prefs.getLong(KEY_LAST_SUCCESS, 0L),
        latestVersion = prefs.getString(KEY_LATEST_VERSION, null),
        downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, null),
        sizeBytes = prefs.getLong(KEY_SIZE_BYTES, 0L),
        sha256 = prefs.getString(KEY_SHA256, null),
        releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "") ?: "",
        upToDateVersion = prefs.getString(KEY_UP_TO_DATE_VERSION, null),
        dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null),
        autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK, true)
    )

    private fun publish() {
        _state.value = read()
    }

    companion object {
        private const val PREFS_NAME = "sherwood_update_check"

        private const val KEY_LAST_ATTEMPT = "last_attempt_at"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_DOWNLOAD_URL = "latest_download_url"
        private const val KEY_SIZE_BYTES = "latest_size_bytes"
        private const val KEY_SHA256 = "latest_sha256"
        private const val KEY_RELEASE_NOTES = "latest_release_notes"
        private const val KEY_UP_TO_DATE_VERSION = "up_to_date_version"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"

        @Volatile
        private var instance: UpdateCheckStore? = null

        fun getInstance(context: Context): UpdateCheckStore =
            instance ?: synchronized(this) {
                instance ?: UpdateCheckStore(context.applicationContext).also { instance = it }
            }
    }
}
