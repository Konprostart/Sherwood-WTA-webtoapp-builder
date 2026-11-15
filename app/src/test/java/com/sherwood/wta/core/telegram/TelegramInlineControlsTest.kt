package com.sherwood.wta.core.telegram

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.studio.git.GitPipelineRun
import org.junit.Before
import org.junit.Test

/**
 * The inline keyboard is a second entry point into the command surface, so it is tested
 * like one: the callback vocabulary is a whitelist, and the CI watcher must announce a
 * run once and only when it actually just finished.
 */
class TelegramInlineControlsTest {

    private fun run(
        id: Long,
        status: String,
        conclusion: String? = null
    ) = GitPipelineRun(
        id = id,
        name = "Android CI",
        workflowId = "ci.yml",
        workflowPath = ".github/workflows/ci.yml",
        status = status,
        conclusion = conclusion,
        branch = "main",
        headSha = "a".repeat(40),
        event = "push",
        runNumber = id.toInt(),
        displayTitle = "CI",
        createdAt = null,
        updatedAt = null,
        htmlUrl = "https://example.test/runs/$id"
    )

    @Before
    fun reset() {
        TelegramBuildNotifier.resetForTest()
    }

    @Test
    fun `callback vocabulary is a whitelist`() {
        assertThat(TelegramControls.isValid(TelegramControls.STATUS)).isTrue()
        assertThat(TelegramControls.isValid(TelegramControls.RUNS)).isTrue()
        assertThat(TelegramControls.isValid(TelegramControls.REPOS)).isTrue()
        assertThat(TelegramControls.isValid(TelegramControls.VAULT)).isTrue()
        assertThat(TelegramControls.isValid(TelegramControls.APK)).isTrue()
        assertThat(TelegramControls.isValid(TelegramControls.BUILD)).isTrue()
        assertThat(TelegramControls.isValid("bd:ci.yml")).isTrue()

        // Anything that is not one of ours must be inert, never forwarded anywhere.
        assertThat(TelegramControls.isValid("../../etc/passwd")).isFalse()
        assertThat(TelegramControls.isValid("https://evil.test")).isFalse()
        assertThat(TelegramControls.isValid("bd:ci.yml trailing")).isFalse()
        assertThat(TelegramControls.isValid("")).isFalse()
    }

    @Test
    fun `workflow ids are validated before they become callback data`() {
        assertThat(TelegramControls.isValidWorkflowId("android-ci.yml")).isTrue()
        assertThat(TelegramControls.isValidWorkflowId("release/play.yml")).isTrue()
        assertThat(TelegramControls.isValidWorkflowId("a b")).isFalse()
        assertThat(TelegramControls.isValidWorkflowId("$" + "{injection}")).isFalse()
        assertThat(TelegramControls.buildData(null)).isEqualTo(TelegramControls.BUILD)
        assertThat(TelegramControls.buildData("ci.yml")).isEqualTo("bd:ci.yml")
        assertThat(TelegramControls.buildData("bad id")).isNull()
        // A run-specific download is preferred over "whatever is newest".
        assertThat(TelegramControls.apkData(42L)).isEqualTo("ap:42")
        assertThat(TelegramControls.apkData(null)).isEqualTo(TelegramControls.APK)
        assertThat(TelegramControls.isValid("ap:42")).isTrue()
        assertThat(TelegramControls.isValid("ap:abc")).isFalse()
    }

    @Test
    fun `a callback button over the 64 byte limit is rejected`() {
        val tooLong = TelegramButton.callback("x", "bd:" + "a".repeat(70))
        val atLimit = TelegramButton.callback("x", "a".repeat(64))
        assertThat(tooLong.isValid).isFalse()
        assertThat(atLimit.isValid).isTrue()
    }

    @Test
    fun `url buttons must be https`() {
        assertThat(TelegramButton.link("run", "https://example.test/1").isValid).isTrue()
        assertThat(TelegramButton.link("run", "http://example.test/1").isValid).isFalse()
        assertThat(TelegramButton.link("run", "").isValid).isFalse()
    }

    @Test
    fun `build result keyboard offers download and run link when available`() {
        val rows = TelegramKeyboards.buildResult(
            "https://example.test/runs/1",
            hasDownload = true,
            runId = 42L
        )
        val buttons = rows.flatten()
        assertThat(buttons.map { it.data }).contains("ap:42")
        assertThat(buttons.any { it.url == "https://example.test/runs/1" }).isTrue()
        // Status is always reachable, so no notification is a dead end.
        assertThat(buttons.map { it.data }).contains(TelegramControls.STATUS)
    }

    @Test
    fun `build result keyboard omits what is not available`() {
        val none = TelegramKeyboards.buildResult(runUrl = null, hasDownload = false).flatten()
        assertThat(none.map { it.data }).containsExactly(TelegramControls.STATUS)
        val badUrl = TelegramKeyboards.buildResult("javascript:alert(1)", hasDownload = false).flatten()
        assertThat(badUrl.any { it.url != null }).isFalse()
    }

    @Test
    fun `a run already finished on the first sweep is recorded but not announced`() {
        val (finished, next) = TelegramRunWatch.sweep(
            previous = emptyMap(),
            current = listOf(run(1, status = "completed", conclusion = "success"))
        )
        assertThat(finished).isEmpty()
        assertThat(next[1L]).isEqualTo("success")
    }

    @Test
    fun `a run observed running and then finished is announced`() {
        val running = listOf(run(2, status = "in_progress"))
        val (firstFinished, state) = TelegramRunWatch.sweep(emptyMap(), running)
        assertThat(firstFinished).isEmpty()

        val (finished, _) = TelegramRunWatch.sweep(
            previous = state,
            current = listOf(run(2, status = "completed", conclusion = "failure"))
        )
        assertThat(finished).hasSize(1)
        assertThat(finished[0].id).isEqualTo(2L)
    }

    @Test
    fun `gitea terminal status is recognised without a completed word`() {
        val (_, state) = TelegramRunWatch.sweep(emptyMap(), listOf(run(3, status = "running")))
        val (finished, _) = TelegramRunWatch.sweep(
            previous = state,
            current = listOf(run(3, status = "success"))
        )
        assertThat(finished).hasSize(1)
    }

    @Test
    fun `a run that stays terminal is not announced twice`() {
        val (_, state) = TelegramRunWatch.sweep(emptyMap(), listOf(run(4, status = "queued")))
        val (first, afterFirst) = TelegramRunWatch.sweep(
            previous = state,
            current = listOf(run(4, status = "completed", conclusion = "success"))
        )
        val (second, _) = TelegramRunWatch.sweep(
            previous = afterFirst,
            current = listOf(run(4, status = "completed", conclusion = "success"))
        )
        assertThat(first).hasSize(1)
        assertThat(second).isEmpty()
    }

    @Test
    fun `notifier announces each run id once`() {
        assertThat(TelegramBuildNotifier.markNotified(10L)).isTrue()
        assertThat(TelegramBuildNotifier.markNotified(10L)).isFalse()
        assertThat(TelegramBuildNotifier.isNotified(10L)).isTrue()
        assertThat(TelegramBuildNotifier.markNotified(11L)).isTrue()
    }
}
