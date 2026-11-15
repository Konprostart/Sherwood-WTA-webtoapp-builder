package com.sherwood.wta.core.agent.tool.builtin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.permission.PermissionChecker
import com.sherwood.wta.core.agent.permission.PermissionDecision
import com.sherwood.wta.core.agent.permission.PermissionMode
import com.sherwood.wta.core.agent.permission.PermissionPrompter
import com.sherwood.wta.core.agent.permission.PermissionResponse
import com.sherwood.wta.core.agent.plan.PlanManager
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.tool.ToolRegistryFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The create-repository gate: a valid name, a private-by-default visibility, a measured
 * copy that is refused rather than truncated, an independent (never forked) repository,
 * and a fresh approval tap on every call.
 */
@RunWith(RobolectricTestRunner::class)
class GitHubCreateRepositoryTest {

    // ------------------------------------------------------------ name validation

    @Test
    fun `repository names follow gitHub rules and cannot traverse`() {
        assertThat(repositoryNameProblem("sherwood-rebrand")).isNull()
        assertThat(repositoryNameProblem("My_App.v2")).isNull()
        assertThat(repositoryNameProblem("  spaced  ")).isNull() // trimmed before the check

        assertThat(repositoryNameProblem(null)).isNotNull()
        assertThat(repositoryNameProblem("")).isNotNull()
        assertThat(repositoryNameProblem("   ")).isNotNull()
        // A path separator would escape the working-copy folder named after it.
        assertThat(repositoryNameProblem("a/b")).isNotNull()
        assertThat(repositoryNameProblem("..")).isNotNull()
        assertThat(repositoryNameProblem("a..b")).isNotNull()
        assertThat(repositoryNameProblem(".")).isNotNull()
        assertThat(repositoryNameProblem("has space")).isNotNull()
        assertThat(repositoryNameProblem("emoji-🎉")).isNotNull()
        assertThat(repositoryNameProblem("---")).isNotNull()
        assertThat(repositoryNameProblem("a".repeat(101))).isNotNull()
        // 100 characters is exactly GitHub's limit.
        assertThat(repositoryNameProblem("a".repeat(100))).isNull()
    }

    @Test
    fun `a description must be one line within gitHub's limit`() {
        assertThat(repositoryDescriptionProblem(null)).isNull()
        assertThat(repositoryDescriptionProblem("A rebranded utility")).isNull()
        assertThat(repositoryDescriptionProblem("d".repeat(MAX_REPOSITORY_DESCRIPTION))).isNull()
        assertThat(repositoryDescriptionProblem("d".repeat(MAX_REPOSITORY_DESCRIPTION + 1)))
            .isNotNull()
        assertThat(repositoryDescriptionProblem("two\nlines")).isNotNull()
        assertThat(repositoryDescriptionProblem("carriage\rreturn")).isNotNull()
    }

    @Test
    fun `visibility defaults to private and only accepts the two words`() {
        // The default must never publish by accident.
        assertThat(parseRepositoryVisibility(null)).isEqualTo(RepositoryVisibility.PRIVATE)
        assertThat(parseRepositoryVisibility("")).isEqualTo(RepositoryVisibility.PRIVATE)
        assertThat(parseRepositoryVisibility("private")).isEqualTo(RepositoryVisibility.PRIVATE)
        assertThat(parseRepositoryVisibility("PUBLIC")).isEqualTo(RepositoryVisibility.PUBLIC)
        assertThat(parseRepositoryVisibility(" Public ")).isEqualTo(RepositoryVisibility.PUBLIC)
        assertThat(parseRepositoryVisibility("internal")).isNull()
        assertThat(RepositoryVisibility.PRIVATE.isPrivate).isTrue()
        assertThat(RepositoryVisibility.PUBLIC.isPrivate).isFalse()
    }

    // --------------------------------------------------------------- copy planning

    @Test
    fun `copy paths skip git plumbing, the metadata file and workflows`() {
        assertThat(isExcludedCopyPath(".git/config")).isTrue()
        assertThat(isExcludedCopyPath("sub/.git/HEAD")).isTrue()
        assertThat(isExcludedCopyPath(".changes/snapshot")).isTrue()
        assertThat(isExcludedCopyPath("repo_bridge.json")).isTrue()
        assertThat(isExcludedCopyPath("repo_bridge.json.tmp")).isTrue()
        assertThat(isExcludedCopyPath("index.html")).isFalse()
        assertThat(isExcludedCopyPath("src/repo_bridge.json")).isFalse()

        // A copied pipeline could run with the user's credentials on the next push.
        assertThat(isWorkflowCopyPath(".github/workflows/build.yml")).isTrue()
        assertThat(isWorkflowCopyPath(".github/workflows")).isTrue()
        assertThat(isWorkflowCopyPath(".github/ISSUE_TEMPLATE/bug.md")).isFalse()
        assertThat(isWorkflowCopyPath("workflows/build.yml")).isFalse()
    }

    @Test
    fun `the scan measures the real files and skips plumbing and workflows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = tempDir(context, "create-scan")
        try {
            File(root, "index.html").writeText("<html></html>")
            File(root, "src").mkdirs()
            File(root, "src/app.js").writeText("console.log(1)")
            File(root, ".git").mkdirs()
            File(root, ".git/config").writeText("[core]")
            File(root, ".changes").mkdirs()
            File(root, ".changes/snapshot").writeText("old")
            File(root, "repo_bridge.json").writeText("{}")
            File(root, ".github/workflows").mkdirs()
            File(root, ".github/workflows/build.yml").writeText("on: push")
            File(root, ".github/README.md").writeText("kept")

            val scan = scanSourceTree(root)

            assertThat(scan.refusal).isNull()
            assertThat(scan.files.map { it.path })
                .containsExactly(".github/README.md", "index.html", "src/app.js")
            assertThat(scan.skippedWorkflows).isEqualTo(1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the scan cannot escape the source tree through a symlink`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = tempDir(context, "create-link")
        try {
            File(root, "real.txt").writeText("real")
            val outside = File(context.cacheDir, "create-outside.txt").apply { writeText("secret") }
            val linked = runCatching {
                java.nio.file.Files.createSymbolicLink(
                    File(root, "escape.txt").toPath(),
                    outside.toPath()
                )
            }.isSuccess
            if (!linked) return

            val scan = scanSourceTree(root)

            assertThat(scan.files.map { it.path }).containsExactly("real.txt")
            assertThat(scan.skippedSymlinks).isEqualTo(1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the scan refuses an oversized file instead of dropping it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = tempDir(context, "create-large")
        try {
            File(root, "big.bin").writeBytes(ByteArray((MAX_COPY_FILE_BYTES + 1).toInt()))
            File(root, "ok.txt").writeText("ok")

            val scan = scanSourceTree(root)

            // A dropped file would make the copy silently look complete.
            assertThat(scan.refusal).contains("big.bin")
            assertThat(scan.files).isEmpty()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the scan refuses a tree over the file cap instead of truncating it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = tempDir(context, "create-cap")
        try {
            repeat(MAX_COPY_FILES + 1) { index -> File(root, "f$index.txt").writeText("x") }

            val scan = scanSourceTree(root)

            assertThat(scan.refusal).contains("$MAX_COPY_FILES")
            // Fewer than the cap is measured; the publish is refused, never partial.
            assertThat(scan.files.size).isAtMost(MAX_COPY_FILES)
        } finally {
            root.deleteRecursively()
        }
    }

    // --------------------------------------------------------------- registration

    @Test
    fun `the create tool is registered once as a write tool that always asks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planManager = PlanManager(
            sessionId = "test",
            fileManager = ProjectFileManager(context),
            permissionChecker = PermissionChecker(PermissionPrompter())
        )
        val registry = ToolRegistryFactory(planManager, imageRegistry = null)
            .build(hasImageModel = false)

        assertThat(registry.all.count { it.name == "GitHubCreateRepository" }).isEqualTo(1)
        val tool = registry.all.first { it.name == "GitHubCreateRepository" }
        assertThat(tool.isReadOnly()).isFalse()
        // A repository is created on a remote account: one tap per call.
        assertThat(tool.requiresFreshApproval()).isTrue()
    }

    @Test
    fun `the description states the independence, copy and approval rules`() {
        val description = GitHubCreateRepositoryTool().description
        assertThat(description).contains("independent")
        assertThat(description).contains("never a fork")
        assertThat(description).contains("fresh tap")
        assertThat(description).contains("PUBLIC")
        assertThat(description).contains(".github/workflows")
        assertThat(description).contains("$MAX_COPY_FILES")
    }

    // -------------------------------------------------------------- approval gate

    @Test
    fun `auto approve still asks before creating a repository`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prompter = PermissionPrompter()
            val checker = PermissionChecker(prompter)
            checker.setMode(PermissionMode.AutoApprove)
            val prompts = AtomicInteger()

            val job = launch(Dispatchers.Default) {
                prompter.requests.collect { request ->
                    prompts.incrementAndGet()
                    // The dialog must not offer "Always allow" for this tool.
                    assertThat(request.allowAlways).isFalse()
                    prompter.respond(request.toolCallId, PermissionResponse.Deny)
                }
            }
            try {
                val decision = checker.check(
                    GitHubCreateRepositoryTool(),
                    JsonObject().apply { addProperty("name", "rebrand") },
                    toolContext(context, prompter)
                )
                assertThat(decision).isEqualTo(PermissionDecision.Deny)
                assertThat(prompts.get()).isEqualTo(1)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `create refuses to run without a stored token and touches no network`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val result = GitHubCreateRepositoryTool().execute(
                JsonObject().apply {
                    addProperty("name", "rebrand")
                    addProperty("visibility", "public")
                },
                toolContext(context, PermissionPrompter())
            )
            assertThat(result.isError).isFalse()
            assertThat(result.text).contains("not connected")
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun tempDir(context: Context, name: String): File =
        File(context.cacheDir, name).apply {
            deleteRecursively()
            mkdirs()
        }
}
