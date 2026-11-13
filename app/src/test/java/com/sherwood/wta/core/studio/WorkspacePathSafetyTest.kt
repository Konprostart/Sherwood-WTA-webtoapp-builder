package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Path containment for the Studio working copy.
 *
 * The old implementation called `java.io.File.toPath()` (API 26) inside a
 * `runCatching`, so on Android 7.x every check silently returned `false` and the
 * diff screen reported `InvalidPath` for every file. These tests lock in both the
 * behaviour (traversal is rejected, siblings are not confused with descendants)
 * and the API-24 safety (no `java.nio.file` usage in the source).
 */
class WorkspacePathSafetyTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun directChildIsInside() {
        val root = temp.newFolder("project")
        val child = File(root, "src/Main.kt")
        child.parentFile!!.mkdirs()
        assertThat(WorkspaceService.isInsidePath(root, child)).isTrue()
    }

    @Test
    fun parentIsInsideItself() {
        val root = temp.newFolder("project")
        assertThat(WorkspaceService.isInsidePath(root, root)).isTrue()
    }

    @Test
    fun siblingWithCommonPrefixIsNotInside() {
        val root = temp.newFolder("pods")
        val sibling = temp.newFolder("pods2")
        val file = File(sibling, "evil.kt")
        file.writeText("x")
        assertThat(WorkspaceService.isInsidePath(root, file)).isFalse()
    }

    @Test
    fun traversalIsRejected() {
        val root = temp.newFolder("project")
        val outside = File(root.parentFile, "outside.txt")
        outside.writeText("secret")
        val escaped = File(root, "../outside.txt")
        assertThat(WorkspaceService.isInsidePath(root, escaped)).isFalse()
    }

    @Test
    fun absolutePathOutsideIsRejected() {
        val root = temp.newFolder("project")
        val outside = temp.newFile("outside.txt")
        assertThat(WorkspaceService.isInsidePath(root, outside)).isFalse()
    }

    @Test
    fun symlinkEscapeIsRejected() {
        val root = temp.newFolder("project")
        val outside = temp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        val link = File(root, "link")
        val created = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Throwable) {
            false
        }
        // Some CI filesystems (and Windows without privileges) forbid symlinks.
        assumeTrue("symlinks unavailable on this filesystem", created)
        assertThat(WorkspaceService.isInsidePath(root, File(link, "secret.txt"))).isFalse()
        assertThat(secret.exists()).isTrue()
    }

    /**
     * Structural guard: the containment code must not reach for `java.nio.file`,
     * which is API 26 and would fail exactly the way this fix repaired. A normal
     * unit test cannot observe the `NoSuchMethodError` on the JVM, so the source is
     * checked instead.
     */
    @Test
    fun studioSourcesAvoidApi26Nio() {
        val studioDir = File("src/main/java/com/sherwood/wta/core/studio")
        assumeTrue("studio sources unavailable", studioDir.isDirectory)
        val offenders = studioDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val trimmed = line.trim()
                    val isComment = trimmed.startsWith("//") ||
                        trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    !isComment && (
                        trimmed.contains("toPath()") ||
                            trimmed.contains("java.nio.file.Files") ||
                            trimmed.contains("java.nio.file.Paths")
                        )
                }
            }
            .map { it.name }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
