package com.sherwood.wta.core.studio.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CI verdict normalisation. The failure this pins: Gitea reports a broken check as
 * `conclusion = "error"` on a `completed` status, which used to count as neither failing
 * nor passing, so the card could show a green build over a red check.
 */
class GitChecksTest {

    private fun check(status: String, conclusion: String?) =
        GitCheck(name = "ci", status = status, conclusion = conclusion, detailsUrl = null)

    @Test
    fun `gitea error and failed conclusions are failures`() {
        assertThat(GitChecks.isFailing("error")).isTrue()
        assertThat(GitChecks.isFailing("failed")).isTrue()
        assertThat(GitChecks.isFailing("success")).isFalse()
        assertThat(GitChecks.isFailing(null)).isFalse()
    }

    @Test
    fun `a completed check with an unknown conclusion is not a pass`() {
        val summary = GitChecks.summarize(
            checks = listOf(check("completed", "something-new")),
            combinedState = null
        )

        assertThat(summary.failing).isEqualTo(0)
        assertThat(summary.passing).isEqualTo(0)
        assertThat(summary.state).isEqualTo(ChecksState.UNKNOWN)
    }

    @Test
    fun `one failing check makes the whole set failing`() {
        val summary = GitChecks.summarize(
            checks = listOf(check("completed", "success"), check("completed", "error")),
            combinedState = null
        )

        assertThat(summary.failing).isEqualTo(1)
        assertThat(summary.state).isEqualTo(ChecksState.FAILING)
    }

    @Test
    fun `all-passing checks are green`() {
        val summary = GitChecks.summarize(
            checks = listOf(check("completed", "success"), check("completed", "skipped")),
            combinedState = "success"
        )

        assertThat(summary.state).isEqualTo(ChecksState.PASSING)
    }

    @Test
    fun `one terminal-state definition covers the statuses both hosts use`() {
        listOf("completed", "success", "failure", "failed", "cancelled", "canceled",
            "skipped", "blocked", "error", "timed_out", "timedout").forEach { status ->
            assertThat(GitPipelineRun.isTerminalStatus(status)).isTrue()
        }
        assertThat(GitPipelineRun.isTerminalStatus("queued")).isFalse()
        assertThat(GitPipelineRun.isTerminalStatus("in_progress")).isFalse()
    }
}
