package com.sherwood.wta.core.update

/**
 * When the app is allowed to talk to the release API.
 *
 * Two independent clocks, because automatic and manual checks have different costs:
 *
 *  - **Automatic** checks are invisible, so they are throttled by a long interval
 *    ([AUTO_CHECK_INTERVAL_HOURS]) measured from the last *successful* check. A failed attempt
 *    must not silence the app for a day, so failures only move the attempt clock.
 *  - **Manual** checks are user-initiated and may be tapped repeatedly; a short floor
 *    ([MANUAL_CHECK_FLOOR_MS]) keeps a double tap or an impatient user from spending the
 *    unauthenticated GitHub quota (60 requests/hour) one request at a time.
 *
 * Pure (no clock, no I/O) so the rules are unit-tested directly. Callers pass `now`.
 */
object UpdateCheckPolicy {

    /** Automatic checks run at most this often. */
    const val AUTO_CHECK_INTERVAL_HOURS = 24

    /** Manual checks reuse the cached answer for this long instead of re-querying. */
    const val MANUAL_CHECK_FLOOR_MS = 30_000L

    /**
     * A failed automatic check is retried no sooner than this. Without it a permanently offline
     * device would run the whole mirror race on every cold start, because a failure never advances
     * the success clock.
     */
    const val AUTO_RETRY_FLOOR_MS = 30L * 60L * 1000L

    /**
     * True when a silent check may run: automatic checks are enabled, no attempt happened inside
     * the retry floor, and no successful check happened within the interval. A check that never
     * succeeded is due once the retry floor has passed.
     */
    fun isAutoCheckDue(
        nowMillis: Long,
        lastSuccessAtMillis: Long,
        autoCheckEnabled: Boolean = true,
        lastAttemptAtMillis: Long = 0L
    ): Boolean {
        if (!autoCheckEnabled) return false
        if (lastAttemptAtMillis > 0L && nowMillis >= lastAttemptAtMillis &&
            nowMillis - lastAttemptAtMillis < AUTO_RETRY_FLOOR_MS
        ) {
            return false
        }
        if (lastSuccessAtMillis <= 0L) return true
        return nowMillis - lastSuccessAtMillis >= AUTO_CHECK_INTERVAL_HOURS * 60L * 60L * 1000L
    }

    /**
     * True when a manual check may hit the network. Below the floor the caller must render the
     * cached result (or check, when nothing is cached) instead of issuing another request.
     */
    fun isManualCheckAllowed(nowMillis: Long, lastAttemptAtMillis: Long): Boolean {
        if (lastAttemptAtMillis <= 0L) return true
        // A clock that moved backwards (manual time change, timezone juggling) must not lock the
        // user out of checking forever — treat it as "due" instead of comparing negative deltas.
        if (nowMillis < lastAttemptAtMillis) return true
        return nowMillis - lastAttemptAtMillis >= MANUAL_CHECK_FLOOR_MS
    }
}
