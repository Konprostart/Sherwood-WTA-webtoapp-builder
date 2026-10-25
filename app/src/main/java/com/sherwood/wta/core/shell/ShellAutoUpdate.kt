package com.sherwood.wta.core.shell

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.sherwood.wta.BuildConfig
import com.sherwood.wta.WebToAppApplication
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.update.AppUpdateController
import com.sherwood.wta.core.update.AppUpdateController.DownloadOutcome
import com.sherwood.wta.core.update.AppUpdateManifest
import com.sherwood.wta.data.model.AppUpdateConfig
import java.io.File
import kotlinx.coroutines.launch

/**
 * Auto-update for generated apps. Runs only in the shell template. Checks the signed manifest at
 * most once per configured interval, asks the user, downloads the APK over HTTPS, verifies its
 * SHA-256 and hands it to the system installer. Android still asks the user to confirm the install.
 */
object ShellAutoUpdate {

    private const val TAG = "ShellAutoUpdate"
    private const val PREFS = "wta_auto_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val UPDATE_DIR = "auto_update"
    private const val UPDATE_FILE = "update.apk"

    fun checkIfDue(activity: Activity, scope: LifecycleCoroutineScope) {
        if (!BuildConfig.SHELL_RUNTIME_ONLY) return
        val config = WebToAppApplication.shellMode.getConfig() ?: return
        val cfg = AppUpdateConfig(
            enabled = config.autoUpdateEnabled,
            manifestUrl = config.autoUpdateManifestUrl,
            publicKeyBase64 = config.autoUpdatePublicKey,
            checkIntervalHours = config.autoUpdateCheckIntervalHours
        )
        if (!cfg.isUsable) return

        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!AppUpdateManifest.isCheckDue(now, prefs.getLong(KEY_LAST_CHECK, 0L), cfg.checkIntervalHours)) return
        // Record the attempt first, so a failing check still waits for the next interval.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        scope.launch {
            val installed = installedVersionCode(activity)
            when (val verdict = AppUpdateController.check(cfg, installed)) {
                is AppUpdateManifest.Verdict.Update ->
                    if (!activity.isFinishing) askToUpdate(activity, scope, verdict.manifest)
                is AppUpdateManifest.Verdict.Rejected ->
                    AppLogger.w(TAG, "Update check rejected: ${verdict.reason}")
                AppUpdateManifest.Verdict.UpToDate ->
                    AppLogger.d(TAG, "Installed version is current")
            }
        }
    }

    /** Fail-safe: if the version cannot be read, no update is offered. */
    private fun installedVersionCode(activity: Activity): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
    }.getOrDefault(Long.MAX_VALUE)

    private fun askToUpdate(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        manifest: AppUpdateManifest.Manifest
    ) {
        AlertDialog.Builder(activity)
            .setTitle(Strings.updateAvailableTitle)
            .setMessage("${Strings.updateNewVersionLabel}: ${manifest.versionName}")
            .setPositiveButton(Strings.updateDownloadButton) { _, _ -> download(activity, scope, manifest) }
            .setNegativeButton(Strings.cancel, null)
            .show()
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, manifest: AppUpdateManifest.Manifest) {
        val target = File(File(activity.cacheDir, UPDATE_DIR), UPDATE_FILE)
        scope.launch {
            when (val outcome = AppUpdateController.downloadApk(manifest, target)) {
                is DownloadOutcome.Ready -> install(activity, outcome.file)
                DownloadOutcome.IntegrityMismatch -> toast(activity, Strings.updateVerificationFailed)
                is DownloadOutcome.Failed -> toast(activity, Strings.updateDownloadFailed)
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "No installer available: ${e.message}")
            toast(activity, Strings.updateDownloadFailed)
        }
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
