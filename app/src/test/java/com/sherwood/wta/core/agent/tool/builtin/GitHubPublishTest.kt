package com.sherwood.wta.core.agent.tool.builtin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.permission.PermissionChecker
import com.sherwood.wta.core.agent.permission.PermissionDecision
import com.sherwood.wta.core.agent.permission.PermissionMode
import com.sherwood.wta.core.agent.permission.PermissionPrompter
import com.sherwood.wta.core.agent.permission.PermissionResponse
import com.sherwood.wta.core.agent.plan.PlanManager
import com.sherwood.wta.core.agent.todo.TodoManager
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.core.agent.tool.ToolRegistryFactory
import com.sherwood.wta.core.agent.tool.ToolResult
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.data.dao.WebAppDao
import com.sherwood.wta.data.dao.WebAppStartupCandidate
import com.sherwood.wta.data.dao.WebAppSummary
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.AppType
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import com.sherwood.wta.data.model.WebApp
import com.sherwood.wta.data.repository.WebAppRepository
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Option 3 guard rails: the agent may send changes to GitHub, but only on a brand-new
 * branch, only through a pull request, only after a fresh approval tap, and never with a
 * force-update. Each of those is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class GitHubPublishTest {

    // ------------------------------------------------------------ branch naming

    @Test
    fun `branch plan takes the conventional prefix and slugifies the title`() {
        assertThat(branchPlanForTitle("feat(studio): make publish safe"))
            .isEqualTo(BranchPlan("feat", "make-publish-safe"))
        assertThat(branchPlanForTitle("Fix: broken launcher icon"))
            .isEqualTo(BranchPlan("fix", "broken-launcher-icon"))
        assertThat(branchPlanForTitle("refactor the git data client"))
            .isEqualTo(BranchPlan("feat", "refactor-the-git-data-client"))
        // A title with no usable ASCII becomes a stable placeholder, not an empty ref.
        assertThat(branchPlanForTitle("docs: 日本語の説明"))
            .isEqualTo(BranchPlan("docs", "changes"))
        assertThat(branchPlanForTitle("")).isEqualTo(BranchPlan("feat", "changes"))
    }

    @Test
    fun `branch plan caps the slug and never leaves a trailing separator`() {
        val plan = branchPlanForTitle("feat: " + "a".repeat(120))
        assertThat(plan.slug.length).isAtMost(48)
        assertThat(plan.slug.endsWith("-")).isFalse()
        assertThat(plan.prefix).isEqualTo("feat")
    }

    @Test
    fun `branch candidates get a numeric suffix only from the second attempt`() {
        val plan = BranchPlan("feat", "publish")
        assertThat(branchNameFor(plan, 0)).isEqualTo("feat/publish")
        assertThat(branchNameFor(plan, 1)).isEqualTo("feat/publish-2")
        assertThat(branchNameFor(plan, 4)).isEqualTo("feat/publish-5")
    }

    @Test
    fun `a task branch may never be the base or a protected branch`() {
        assertThat(newBranchProblem("feat/safe-change", "feat/sherwood-studio")).isNull()
        assertThat(newBranchProblem("fix/thing-2", "main")).isNull()

        // Rule 1: never the base the folder was imported from, never main/master.
        assertThat(newBranchProblem("feat/sherwood-studio", "feat/sherwood-studio")).isNotNull()
        assertThat(newBranchProblem("main", "main")).isNotNull()
        assertThat(newBranchProblem("master", "main")).isNotNull()
        // No tool/agent namespace either — the branch belongs to the repository.
        assertThat(newBranchProblem("codex/thing", "main")).isNotNull()
        // Unprefixed, empty-slug and uppercase names never reach POST /git/refs.
        assertThat(newBranchProblem("thing", "main")).isNotNull()
        assertThat(newBranchProblem("feat/", "main")).isNotNull()
        assertThat(newBranchProblem("feat/Upper", "main")).isNotNull()
        // Git's own limits are enforced first: `..` and `.lock` are refused.
        assertThat(newBranchProblem("feat/..", "main")).isNotNull()
        assertThat(newBranchProblem("feat/x.lock", "main")).isNotNull()
        assertThat(newBranchProblem("feat/" + "a".repeat(90), "main")).isNotNull()
    }

    // ------------------------------------------------------------- session copy

    @Test
    fun `copy carries edits but never git, changes or the repository metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = tempDir(context, "publish-source")
        val target = tempDir(context, "publish-target")
        try {
            File(source, "index.html").writeText("<html>edited</html>")
            File(source, "src").mkdirs()
            File(source, "src/app.js").writeText("console.log('edited')")
            File(source, ".git").mkdirs()
            File(source, ".git/config").writeText("[core]")
            File(source, ".changes").mkdirs()
            File(source, ".changes/snapshot").writeText("old")
            // The import metadata must never overwrite the working copy's own record.
            RepoBridgeMetadataStore().write(
                source,
                metadata(owner = "sweatoken", repo = "repo", ref = "feat/x", baseSha = SHA_A)
            )
            File(target, "kept.txt").writeText("untouched")

            val stats = copySessionTree(source, target)

            assertThat(stats.files).isEqualTo(2)
            assertThat(stats.failed).isEqualTo(0)
            assertThat(File(target, "index.html").readText()).isEqualTo("<html>edited</html>")
            assertThat(File(target, "src/app.js").readText()).isEqualTo("console.log('edited')")
            assertThat(File(target, ".git/config").exists()).isFalse()
            assertThat(File(target, ".changes/snapshot").exists()).isFalse()
            assertThat(File(target, RepoBridgeMetadataStore.FILE_NAME).exists()).isFalse()
            assertThat(File(target, "kept.txt").readText()).isEqualTo("untouched")
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun `copy cannot escape the working copy through a symlink`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = tempDir(context, "publish-link-source")
        val target = tempDir(context, "publish-link-target")
        try {
            File(source, "real.txt").writeText("real")
            val outside = File(context.cacheDir, "publish-outside.txt").apply { writeText("secret") }
            val link = File(source, "escape.txt")
            val linked = runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            }.isSuccess

            val stats = copySessionTree(source, target)

            assertThat(File(target, "real.txt").readText()).isEqualTo("real")
            if (linked) {
                assertThat(stats.files).isEqualTo(1)
                assertThat(File(target, "escape.txt").exists()).isFalse()
            }
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun `copy flags a file that is too large to upload instead of dropping it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = tempDir(context, "publish-large-source")
        val target = tempDir(context, "publish-large-target")
        try {
            File(source, "big.bin").writeBytes(ByteArray(10 * 1024 * 1024 + 1))
            File(source, "small.txt").writeText("ok")

            val stats = copySessionTree(source, target)

            // The publish aborts on this count: a skipped file would be missing from the PR.
            assertThat(stats.skippedLarge).isEqualTo(1)
            assertThat(stats.files).isEqualTo(1)
            assertThat(File(target, "big.bin").exists()).isFalse()
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun `copy stops at the file cap and flags truncation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = tempDir(context, "publish-cap-source")
        val target = tempDir(context, "publish-cap-target")
        try {
            repeat(MAX_PUBLISH_FILES + 1) { index ->
                File(source, "f$index.txt").writeText("x")
            }

            val stats = copySessionTree(source, target)

            assertThat(stats.truncated).isTrue()
            assertThat(stats.files).isEqualTo(MAX_PUBLISH_FILES)
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- registration

    @Test
    fun `the publish tool is registered once as a write tool that always asks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planManager = PlanManager(
            sessionId = "test",
            fileManager = ProjectFileManager(context),
            permissionChecker = PermissionChecker(PermissionPrompter())
        )
        val registry = ToolRegistryFactory(planManager, imageRegistry = null)
            .build(hasImageModel = false)
        val names = registry.all.map { it.name }

        assertThat(names.count { it == "GitHubOpenPullRequest" }).isEqualTo(1)
        val tool = registry.all.first { it.name == "GitHubOpenPullRequest" }
        assertThat(tool.isReadOnly()).isFalse()
        // Rule 3: no remembered approval for a push.
        assertThat(tool.requiresFreshApproval()).isTrue()
        // The other GitHub tools keep the plain gate.
        assertThat(registry.all.first { it.name == "GitHubImportRepository" }.requiresFreshApproval())
            .isFalse()
    }

    @Test
    fun `the publish tool description states the branch, pull request and no-force rules`() {
        val description = GitHubOpenPullRequestTool().description
        assertThat(description).contains("NEW branch")
        assertThat(description).contains("pull request")
        assertThat(description).contains("never force-pushes")
        assertThat(description).contains("never merges")
        assertThat(description).contains("English")
    }

    // ----------------------------------------------------------- approval gate

    @Test
    fun `a fresh-approval tool prompts again after always allow`() {
        runBlocking {
            val prompter = PermissionPrompter()
            val checker = PermissionChecker(prompter)
            val prompts = AtomicInteger()
            val allowAlwaysFlags = Collections.synchronizedList(mutableListOf<Boolean>())
            val context = ApplicationProvider.getApplicationContext<Context>()
            val ctx = toolContext(context, prompter)

            val job = launch(Dispatchers.Default) {
                prompter.requests.collect { request ->
                    allowAlwaysFlags += request.allowAlways
                    val seen = prompts.incrementAndGet()
                    prompter.respond(
                        request.toolCallId,
                        if (seen == 1) PermissionResponse.AlwaysAllow else PermissionResponse.Allow
                    )
                }
            }
            try {
                val tool = FakeTool("FreshWrite", freshApproval = true)
                assertThat(checker.check(tool, JsonObject(), ctx))
                    .isEqualTo(PermissionDecision.Allow)
                assertThat(checker.check(tool, JsonObject(), ctx))
                    .isEqualTo(PermissionDecision.Allow)
                // Two prompts: the AlwaysAllow answered the first call but was not
                // remembered, and the dialog never offered the button in the first place.
                assertThat(prompts.get()).isEqualTo(2)
                assertThat(allowAlwaysFlags).containsExactly(false, false)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `auto approve does not pre-authorize a push but keeps covering ordinary writes`() {
        runBlocking {
            val prompter = PermissionPrompter()
            val checker = PermissionChecker(prompter)
            checker.setMode(PermissionMode.AutoApprove)
            val prompts = AtomicInteger()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val ctx = toolContext(context, prompter)

            val job = launch(Dispatchers.Default) {
                prompter.requests.collect { request ->
                    prompts.incrementAndGet()
                    prompter.respond(request.toolCallId, PermissionResponse.Allow)
                }
            }
            try {
                assertThat(checker.check(FakeTool("OrdinaryWrite", false), JsonObject(), ctx))
                    .isEqualTo(PermissionDecision.Allow)
                // Auto-approve still means what it says for a normal write tool.
                assertThat(prompts.get()).isEqualTo(0)

                assertThat(checker.check(FakeTool("FreshWrite", true), JsonObject(), ctx))
                    .isEqualTo(PermissionDecision.Allow)
                // Rule 3 survives the setting: the push asked anyway.
                assertThat(prompts.get()).isEqualTo(1)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `an ordinary write tool still remembers always allow`() {
        runBlocking {
            val prompter = PermissionPrompter()
            val checker = PermissionChecker(prompter)
            val prompts = AtomicInteger()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val ctx = toolContext(context, prompter)

            val job = launch(Dispatchers.Default) {
                prompter.requests.collect { request ->
                    prompts.incrementAndGet()
                    prompter.respond(request.toolCallId, PermissionResponse.AlwaysAllow)
                }
            }
            try {
                val tool = FakeTool("OrdinaryWrite", freshApproval = false)
                assertThat(checker.check(tool, JsonObject(), ctx))
                    .isEqualTo(PermissionDecision.Allow)
                assertThat(checker.check(tool, JsonObject(), ctx))
                    .isEqualTo(PermissionDecision.Allow)
                assertThat(prompts.get()).isEqualTo(1)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `publish refuses to run without a stored token and touches no network`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val result = GitHubOpenPullRequestTool().execute(
                JsonObject(),
                toolContext(context, PermissionPrompter())
            )
            assertThat(result.isError).isFalse()
            assertThat(result.text).contains("not connected")
        }
    }

    // ----------------------------------------------------------------- helpers

    private class FakeTool(
        override val name: String,
        private val freshApproval: Boolean
    ) : Tool {
        override val description: String = "test tool"
        override val parametersSchema: JsonElement = JsonObject()
        override fun isReadOnly(): Boolean = false
        override fun requiresFreshApproval(): Boolean = freshApproval
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult.ok("ok")
    }

    private fun tempDir(context: Context, name: String): File =
        File(context.cacheDir, name).apply {
            deleteRecursively()
            mkdirs()
        }

    private fun metadata(
        owner: String,
        repo: String,
        ref: String,
        baseSha: String
    ) = RepoBridgeMetadata(
        host = RepoBridgeMetadata.HOST_GITHUB,
        owner = owner,
        repo = repo,
        ref = ref,
        baseSha = baseSha
    )

    private companion object {
        const val SHA_A = "0123456789abcdef0123456789abcdef01234567"
    }
}
