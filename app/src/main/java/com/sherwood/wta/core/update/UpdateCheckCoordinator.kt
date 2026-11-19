package com.sherwood.wta.core.update

import android.content.Context
import android.content.pm.PackageManager
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.EndpointConfig
import com.sherwood.wta.core.studio.EndpointSettingsStore

/**
 * The one place that decides *whether* to ask GitHub for a release and what to remember from the
 * answer. Callers (About screen, app startup) stay free of throttling rules.
 *
 * Both entry points never throw: a network failure is a [UpdateChecker.Result.Failed] value, so
 * a silent automatic check can be launched without a try/catch and a manual one still renders a
 * clear status.
 */
object UpdateCheckCoordinator {

    private const val TAG = "UpdateCheckCoordinator"

    /** Version name of this installation, from the package manager (flavor-correct). */
    fun installedVersionName(context: Context): String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?: com.sherwood.wta.BuildConfig.VERSION_NAME
    } catch (e: PackageManager.NameNotFoundException) {
        com.sherwood.wta.BuildConfig.VERSION_NAME
    }

    /**
     * Runs a silent check when [UpdateCheckPolicy.isAutoCheckDue] allows it, otherwise returns
     * null without touching the network. The cached state is published through [store].
     */
    suspend fun autoCheckIfDue(
        context: Context,
        currentVersionName: String = installedVersionName(context),
        endpoints: EndpointConfig = EndpointSettingsStore.current(context),
        variant: UpdateChecker.Variant = UpdateChecker.Variant.current(),
        nowMillis: Long = System.currentTimeMillis()
    ): UpdateChecker.Result? {
        val store = UpdateCheckStore.getInstance(context)
        val snapshot = store.snapshot()
        if (!UpdateCheckPolicy.isAutoCheckDue(
                nowMillis,
                snapshot.lastSuccessAtMillis,
                snapshot.autoCheckEnabled
            )
        ) {
            return null
        }
        return runCheck(store, currentVersionName, endpoints, variant, nowMillis)
    }

    /**
     * User-requested check. Within [UpdateCheckPolicy.MANUAL_CHECK_FLOOR_MS] the cached verdict
     * is returned when there is one, so repeated taps do not each cost a request; with no cached
     * verdict the request still runs (a first-time user must not stare at an empty dialog).
     */
    suspend fun manualCheck(
        context: Context,
        currentVersionName: String = installedVersionName(context),
        endpoints: EndpointConfig = EndpointSettingsStore.current(context),
        variant: UpdateChecker.Variant = UpdateChecker.Variant.current(),
        nowMillis: Long = System.currentTimeMillis()
    ): UpdateChecker.Result {
        val store = UpdateCheckStore.getInstance(context)
        val snapshot = store.snapshot()
        if (!UpdateCheckPolicy.isManualCheckAllowed(nowMillis, snapshot.lastAttemptAtMillis)) {
            store.cachedResult(currentVersionName)?.let { return it }
        }
        return runCheck(store, currentVersionName, endpoints, variant, nowMillis)
    }

    private suspend fun runCheck(
        store: UpdateCheckStore,
        currentVersionName: String,
        endpoints: EndpointConfig,
        variant: UpdateChecker.Variant,
        nowMillis: Long
    ): UpdateChecker.Result {
        store.recordAttempt(nowMillis)
        return try {
            val result = UpdateChecker.check(currentVersionName, endpoints, variant)
            // Only successful verdicts are cached; recordResult ignores Failed.
            store.recordResult(result, nowMillis)
            if (result is UpdateChecker.Result.Failed) {
                AppLogger.w(TAG, "Update check failed: ${result.message}")
            }
            result
        } catch (e: Exception) {
            // UpdateChecker already maps its own failures; this is belt-and-braces for a
            // cancellation-free caller that must never be taken down by a check.
            AppLogger.w(TAG, "Update check crashed: ${e.message}")
            UpdateChecker.Result.Failed(e.message ?: "Update check failed", e)
        }
    }
}
