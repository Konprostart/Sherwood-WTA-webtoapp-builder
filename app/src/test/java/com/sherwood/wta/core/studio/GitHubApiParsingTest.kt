package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Test

class GitHubApiParsingTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun parsesRepository() {
        val repo = GitHubApiClient.parseRepository(
            json(
                """{"id":7,"name":"demo","full_name":"acme/demo","private":true,
                   "fork":false,"default_branch":"main","description":"d",
                   "owner":{"login":"acme"},"pushed_at":"2026-01-01T00:00:00Z"}"""
            )
        )
        assertThat(repo).isNotNull()
        assertThat(repo!!.ownerRepo).isEqualTo("acme/demo")
        assertThat(repo.privateRepo).isTrue()
        assertThat(repo.defaultBranch).isEqualTo("main")
    }

    @Test
    fun parsesBranch() {
        val branch = GitHubApiClient.parseBranch(
            json("""{"name":"dev","protected":true,"commit":{"sha":"abc"}}""")
        )
        assertThat(branch!!.name).isEqualTo("dev")
        assertThat(branch.sha).isEqualTo("abc")
        assertThat(branch.protectedBranch).isTrue()
    }

    @Test
    fun parsesPullRequest() {
        val pr = GitHubApiClient.parsePullRequest(
            json(
                """{"number":12,"title":"Fix","state":"open","draft":false,
                   "merged":false,"user":{"login":"dev"},
                   "head":{"ref":"fix/x"},"base":{"ref":"main"},
                   "html_url":"https://github.com/acme/demo/pull/12"}"""
            )
        )
        assertThat(pr!!.number).isEqualTo(12)
        assertThat(pr.headRef).isEqualTo("fix/x")
        assertThat(pr.author).isEqualTo("dev")
    }

    @Test
    fun parsesWorkflowRunAndJob() {
        val run = GitHubApiClient.parseWorkflowRun(
            json(
                """{"id":99,"name":"Build","workflow_id":3,"status":"completed",
                   "conclusion":"success","head_branch":"main","head_sha":"abc",
                   "event":"push","run_number":5,"display_title":"Release",
                   "html_url":"https://github.com/acme/demo/actions/runs/99"}"""
            )
        )
        assertThat(run!!.id).isEqualTo(99)
        assertThat(run.conclusion).isEqualTo("success")
        assertThat(run.runNumber).isEqualTo(5)

        val job = GitHubApiClient.parseJob(
            json(
                """{"id":1,"name":"check","status":"completed","conclusion":"success",
                   "steps":[{"number":1,"name":"setup","status":"completed","conclusion":"success"}]}"""
            )
        )
        assertThat(job!!.steps).hasSize(1)
        assertThat(job.steps.first().name).isEqualTo("setup")
    }

    @Test
    fun parsesArtifact() {
        val artifact = GitHubApiClient.parseArtifact(
            json(
                """{"id":4,"name":"apk","size_in_bytes":1234,"expired":false,
                   "archive_download_url":"https://api.github.com/x","created_at":"2026-01-01",
                   "workflow_run":{"id":99}}"""
            )
        )
        assertThat(artifact!!.name).isEqualTo("apk")
        assertThat(artifact.sizeInBytes).isEqualTo(1234)
        assertThat(artifact.workflowRunId).isEqualTo(99)
    }

    @Test
    fun artifactWithoutDownloadUrlIsSkipped() {
        assertThat(GitHubApiClient.parseArtifact(json("""{"id":4,"name":"x"}"""))).isNull()
    }

    @Test
    fun parsesUserAndCommit() {
        val user = GitHubApiClient.parseUser(json("""{"login":"me","name":"Me"}"""))
        assertThat(user!!.login).isEqualTo("me")

        val commit = GitHubApiClient.parseCommit(
            json(
                """{"sha":"abc","html_url":"https://github.com/x/y/commit/abc",
                   "commit":{"message":"subject\n\nbody","author":{"name":"Me","date":"2026"}}}"""
            )
        )
        assertThat(commit!!.message).isEqualTo("subject")
        assertThat(commit.authorName).isEqualTo("Me")
    }
}
