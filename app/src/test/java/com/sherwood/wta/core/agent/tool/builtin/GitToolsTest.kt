package com.sherwood.wta.core.agent.tool.builtin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.permission.PermissionChecker
import com.sherwood.wta.core.agent.permission.PermissionPrompter
import com.sherwood.wta.core.agent.plan.PlanManager
import com.sherwood.wta.core.agent.tool.ToolRegistryFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The agent's GitHub tools must stay read-only towards GitHub and write only inside the
 * chat's session sandbox. These tests pin the parts that are cheap to pin: the repo
 * identifier parser, the copy guard rails, and registration.
 */
@RunWith(RobolectricTestRunner::class)
class GitToolsTest {

    // ------------------------------------------------------------------ parsing

    @Test
    fun `owner repo parser accepts plain and dot git forms`() {
        assertThat(parseOwnerRepo("Konprostart/Sherwood-WTA-webtoapp-builder"))
            .isEqualTo("Konprostart" to "Sherwood-WTA-webtoapp-builder")
        assertThat(parseOwnerRepo("  owner/repo.git  ")).isEqualTo("owner" to "repo")
        assertThat(parseOwnerRepo("a-b/c-d_e.f")).isEqualTo("a-b" to "c-d_e.f")
        // A leading dot is a real repository name on GitHub, not a traversal attempt.
        assertThat(parseOwnerRepo("owner/.github")).isEqualTo("owner" to ".github")
    }

    @Test
    fun `owner repo parser rejects anything that is not owner slash repo`() {
        val rejected = listOf(
            null, "", "   ", "repo", "owner/repo/extra", "/repo", "owner/",
            "owner/re po", "own er/repo", "owner/re:po", "own:er/repo",
            // Traversal segments must never survive parsing: the repository name
            // becomes a folder inside the session sandbox.
            "../repo", "owner/..", "owner/.", "./repo",
            // GitHub account names cannot contain `_`, `.` or a trailing hyphen.
            "own_er/repo", "own.er/repo", "owner-/repo"
        )
        rejected.forEach { value ->
            assertThat(parseOwnerRepo(value)).isNull()
        }
    }

    // ------------------------------------------------------------------ copying

    @Test
    fun `copy skips git metadata and oversized files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = ProjectFileManager(context)
        val sessionId = "github-tools-copy"
        fileManager.deleteSession(sessionId)

        val source = File(context.cacheDir, "github-tools-source").apply {
            deleteRecursively()
            mkdirs()
        }
        File(source, "index.html").writeText("<html></html>")
        File(source, "src").mkdirs()
        File(source, "src/app.js").writeText("console.log(1)")
        File(source, ".git").mkdirs()
        File(source, ".git/config").writeText("[core]")
        // Larger than the 4 MB per-file cap.
        File(source, "big.bin").writeBytes(ByteArray(4 * 1024 * 1024 + 1))

        val stats = copyIntoSession(fileManager, sessionId, source, "repo")

        assertThat(stats.files).isEqualTo(2)
        assertThat(stats.tooLarge).isEqualTo(1)
        assertThat(stats.truncated).isFalse()

        val root = fileManager.getSessionRoot(sessionId)
        assertThat(File(root, "repo/index.html").exists()).isTrue()
        assertThat(File(root, "repo/src/app.js").exists()).isTrue()
        assertThat(File(root, "repo/big.bin").exists()).isFalse()
        assertThat(File(root, "repo/.git/config").exists()).isFalse()
        fileManager.deleteSession(sessionId)
    }

    @Test
    fun `copy cannot escape the session sandbox through a symlink`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = ProjectFileManager(context)
        val sessionId = "github-tools-symlink"
        fileManager.deleteSession(sessionId)

        val source = File(context.cacheDir, "github-tools-symlink-src").apply {
            deleteRecursively()
            mkdirs()
        }
        File(source, "real.html").writeText("<html></html>")
        val outside = File(context.cacheDir, "github-tools-outside.txt").apply {
            writeText("secret")
        }
        val link = File(source, "escape.txt")
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess

        val stats = copyIntoSession(fileManager, sessionId, source, "repo")

        assertThat(stats.files).isEqualTo(1)
        val root = fileManager.getSessionRoot(sessionId)
        assertThat(File(root, "repo/real.html").exists()).isTrue()
        if (linked) {
            assertThat(File(root, "repo/escape.txt").exists()).isFalse()
        }
        fileManager.deleteSession(sessionId)
    }

    // -------------------------------------------------------------- registration

    @Test
    fun `git tools are registered once and keep their read-only flags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planManager = PlanManager(
            sessionId = "test",
            fileManager = ProjectFileManager(context),
            permissionChecker = PermissionChecker(PermissionPrompter())
        )
        val registry = ToolRegistryFactory(planManager, imageRegistry = null)
            .build(hasImageModel = false)
        val names = registry.all.map { it.name }

        assertThat(names.count { it == "GitStatus" }).isEqualTo(1)
        assertThat(names.count { it == "GitListRepositories" }).isEqualTo(1)
        assertThat(names.count { it == "GitImportRepository" }).isEqualTo(1)
        assertThat(names.distinct()).isEqualTo(names)

        assertThat(registry.all.first { it.name == "GitStatus" }.isReadOnly()).isTrue()
        assertThat(registry.all.first { it.name == "GitListRepositories" }.isReadOnly()).isTrue()
        // Importing writes files, so it must go through the permission prompt.
        assertThat(registry.all.first { it.name == "GitImportRepository" }.isReadOnly()).isFalse()
    }

    @Test
    fun `import tool description states that it never pushes`() {
        val description = GitImportRepositoryTool().description
        assertThat(description).contains("does NOT")
        assertThat(description).contains("push")
        // The model must be able to understand the default target without a hint.
        assertThat(description).contains("GitStatus")
    }
}
