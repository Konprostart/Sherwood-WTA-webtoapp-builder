package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Test

/**
 * Pull-request, review-file and CI-check parsing, plus the check-state reducer.
 *
 * These are pure functions: everything here is the logic that decides what the
 * Studio shows for a pull request, so it is testable without a network or a
 * GitHub account.
 */
class GitHubPullRequestParsingTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun parsesPullRequestDetails() {
        val details = GitHubApiClient.parsePullRequestDetails(
            json(
                """{"number":9,"title":"Studio","state":"open","draft":true,"merged":false,
                   "user":{"login":"octocat"},"head":{"ref":"feat/x","sha":"headsha"},
                   "base":{"ref":"main","sha":"basesha"},"html_url":"https://github.com/a/b/pull/9",
                   "mergeable":true,"mergeable_state":"clean","additions":12,"deletions":3,
                   "changed_files":2,"commits":4,"created_at":"2026-01-01T00:00:00Z",
                   "updated_at":"2026-01-02T00:00:00Z"}"""
            )
        )
        assertThat(details).isNotNull()
        requireNotNull(details)
        assertThat(details.pull.number).isEqualTo(9)
        assertThat(details.pull.draft).isTrue()
        assertThat(details.headSha).isEqualTo("headsha")
        assertThat(details.baseSha).isEqualTo("basesha")
        assertThat(details.mergeable).isTrue()
        assertThat(details.changedFiles).isEqualTo(2)
        assertThat(details.commitCount).isEqualTo(4)
    }

    @Test
    fun mergeableStaysNullWhileGitHubComputesIt() {
        val details = GitHubApiClient.parsePullRequestDetails(
            json(
                """{"number":1,"title":"t","state":"open","head":{"ref":"a","sha":"h"},
                   "base":{"ref":"main","sha":"b"},"mergeable":null}"""
            )
        )
        requireNotNull(details)
        // null must survive as "unknown", not become false/"conflicting".
        assertThat(details.mergeable).isNull()
    }

    @Test
    fun parsesPullRequestFile() {
        val file = GitHubApiClient.parsePullRequestFile(
            json(
                """{"filename":"app/src/Main.kt","status":"renamed","additions":5,
                   "deletions":1,"changes":6,"previous_filename":"app/src/Old.kt"}"""
            )
        )
        requireNotNull(file)
        assertThat(file.filename).isEqualTo("app/src/Main.kt")
        assertThat(file.previousFilename).isEqualTo("app/src/Old.kt")
        assertThat(file.changes).isEqualTo(6)
        // GitHub omits `patch` for binary files; absence must stay null.
        assertThat(file.patch).isNull()
    }

    @Test
    fun parsesPullRequestFilePatch() {
        val file = GitHubApiClient.parsePullRequestFile(
            json(
                """{"filename":"a.kt","status":"modified","additions":1,"deletions":0,
                   "changes":1,"patch":"@@ -1,1 +1,2 @@\n ctx\n+new"}"""
            )
        )
        requireNotNull(file)
        assertThat(file.patch).contains("+new")
    }

    @Test
    fun commentableLinesComeFromTheNewSideOnly() {
        val patch = "@@ -10,3 +10,3 @@\n-gone\n+added\n ctx"
        // 10 is the added line, 11 the context line; the removed line does not exist
        // on the new side, so it is never suggested.
        assertThat(GitHubApiClient.commentableNewLines(patch)).containsExactly(10)
    }

    @Test
    fun commentableLinesFollowEachHunkHeader() {
        val patch = "@@ -1,1 +1,2 @@\n+a\n b\n@@ -50,1 +51,1 @@\n+c"
        assertThat(GitHubApiClient.commentableNewLines(patch)).containsExactly(1, 51).inOrder()
    }

    @Test
    fun commentableLinesAreEmptyWithoutAPatch() {
        assertThat(GitHubApiClient.commentableNewLines(null)).isEmpty()
        assertThat(GitHubApiClient.commentableNewLines("")).isEmpty()
        assertThat(GitHubApiClient.commentableNewLines("not a diff\n+a")).isEmpty()
    }

    @Test
    fun commentableLinesRespectTheLimit() {
        val patch = "@@ -1,0 +1,5 @@\n+a\n+b\n+c\n+d\n+e"
        assertThat(GitHubApiClient.commentableNewLines(patch, limit = 3))
            .containsExactly(1, 2, 3)
            .inOrder()
    }

    @Test
    fun parsesReviewComment() {
        val comment = GitHubApiClient.parsePullRequestReviewComment(
            json(
                """{"id":42,"path":"a.kt","line":7,"body":"nit",
                   "user":{"login":"rev"},"html_url":"https://x"}"""
            )
        )
        requireNotNull(comment)
        assertThat(comment.path).isEqualTo("a.kt")
        assertThat(comment.line).isEqualTo(7)
        assertThat(comment.author).isEqualTo("rev")
    }

    @Test
    fun failingCheckWinsOverEverything() {
        val checks = listOf(
            GitHubCheckRun("build", "completed", "success", null),
            GitHubCheckRun("lint", "completed", "failure", null),
            GitHubCheckRun("test", "in_progress", null, null)
        )
        assertThat(GitHubApiClient.checksStateOf(null, checks))
            .isEqualTo(ChecksState.FAILING)
    }

    @Test
    fun pendingCheckWinsOverPassing() {
        val checks = listOf(
            GitHubCheckRun("build", "completed", "success", null),
            GitHubCheckRun("test", "queued", null, null)
        )
        assertThat(GitHubApiClient.checksStateOf(null, checks))
            .isEqualTo(ChecksState.PENDING)
    }

    @Test
    fun allCompletedIsPassing() {
        val checks = listOf(
            GitHubCheckRun("build", "completed", "success", null),
            GitHubCheckRun("lint", "completed", "skipped", null)
        )
        assertThat(GitHubApiClient.checksStateOf(null, checks))
            .isEqualTo(ChecksState.PASSING)
    }

    @Test
    fun noSignalStaysUnknown() {
        assertThat(GitHubApiClient.checksStateOf(null, emptyList()))
            .isEqualTo(ChecksState.UNKNOWN)
        // A branch with no CI at all must never be reported as green.
        assertThat(GitHubApiClient.summarizeChecks(emptyList(), null).state)
            .isEqualTo(ChecksState.UNKNOWN)
    }

    @Test
    fun combinedStatusIsUsedWhenNoCheckRunsExist() {
        assertThat(GitHubApiClient.checksStateOf("success", emptyList()))
            .isEqualTo(ChecksState.PASSING)
        assertThat(GitHubApiClient.checksStateOf("pending", emptyList()))
            .isEqualTo(ChecksState.PENDING)
        assertThat(GitHubApiClient.checksStateOf("failure", emptyList()))
            .isEqualTo(ChecksState.FAILING)
    }

    @Test
    fun cancelledAndTimedOutCountAsFailures() {
        assertThat(GitHubApiClient.isFailing("cancelled")).isTrue()
        assertThat(GitHubApiClient.isFailing("timed_out")).isTrue()
        assertThat(GitHubApiClient.isFailing("action_required")).isTrue()
        assertThat(GitHubApiClient.isFailing("success")).isFalse()
        assertThat(GitHubApiClient.isPassing("neutral")).isTrue()
        assertThat(GitHubApiClient.isPassing("failure")).isFalse()
    }

    @Test
    fun summarizeCountsEachBucket() {
        val checks = listOf(
            GitHubCheckRun("a", "completed", "success", null),
            GitHubCheckRun("b", "completed", "failure", null),
            GitHubCheckRun("c", "in_progress", null, null)
        )
        val summary = GitHubApiClient.summarizeChecks(checks, combinedState = null)
        assertThat(summary.total).isEqualTo(3)
        assertThat(summary.passing).isEqualTo(1)
        assertThat(summary.failing).isEqualTo(1)
        assertThat(summary.pending).isEqualTo(1)
    }
}
