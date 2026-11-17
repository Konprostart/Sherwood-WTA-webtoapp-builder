package com.sherwood.wta.core.studio.git

/**
 * Turns a host's CI signal into one [ChecksState].
 *
 * The rule is the same everywhere and deliberately conservative: failing wins over
 * pending, pending over passing, and no signal at all stays [ChecksState.UNKNOWN].
 * Reporting UNKNOWN instead of PASSING is what keeps the UI from telling the user a
 * build is green when the host has not actually said so.
 *
 * Hosts differ only in how they encode a "pending" check. GitHub has a `status` of
 * `completed` and a separate `conclusion`; Gitea reports a commit status whose own value
 * is pending/success/failure. The drivers normalise that into [GitCheck] before calling
 * here, so this object never has to know which host it came from.
 */
object GitChecks {

    /** `completed` is the only terminal check status on GitHub-shaped checks. */
    fun isCompleted(status: String): Boolean = status.equals("completed", ignoreCase = true)

    fun isPassing(conclusion: String?): Boolean = when (conclusion?.lowercase()) {
        "success", "neutral", "skipped" -> true
        else -> false
    }

    fun isFailing(conclusion: String?): Boolean = when (conclusion?.lowercase()) {
        // `error` and `failed` are the words Gitea's commit statuses use for a red run;
        // without them a completed-but-broken check counted as neither passing nor failing.
        "failure", "failed", "error", "cancelled", "timed_out", "action_required",
        "startup_failure" -> true
        else -> false
    }

    fun summarize(checks: List<GitCheck>, combinedState: String?): GitCheckSummary {
        val pending = checks.count { !isCompleted(it.status) }
        val failing = checks.count { isCompleted(it.status) && isFailing(it.conclusion) }
        val passing = checks.count { isCompleted(it.status) && isPassing(it.conclusion) }
        return GitCheckSummary(
            state = stateOf(combinedState, checks),
            total = checks.size,
            pending = pending,
            passing = passing,
            failing = failing,
            checks = checks,
            combinedState = combinedState
        )
    }

    fun stateOf(combinedState: String?, checks: List<GitCheck>): ChecksState {
        val combined = combinedState?.lowercase()
        if (checks.any { isCompleted(it.status) && isFailing(it.conclusion) }) {
            return ChecksState.FAILING
        }
        if (combined == "failure" || combined == "error") return ChecksState.FAILING
        if (checks.any { !isCompleted(it.status) }) return ChecksState.PENDING
        if (combined == "pending") return ChecksState.PENDING
        // Green requires every completed check to be explicitly passing. "All completed"
        // is not enough: a completed check with an unknown conclusion is not a pass.
        if (checks.isNotEmpty() && checks.all { isCompleted(it.status) && isPassing(it.conclusion) }) {
            return ChecksState.PASSING
        }
        if (combined == "success") return ChecksState.PASSING
        return ChecksState.UNKNOWN
    }
}
