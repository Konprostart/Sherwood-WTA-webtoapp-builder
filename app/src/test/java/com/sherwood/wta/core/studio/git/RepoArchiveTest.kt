package com.sherwood.wta.core.studio.git

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The extraction rules for a downloaded repository archive.
 *
 * A repository archive is untrusted input: a hostile or broken host can put `..` in an
 * entry name, nest a symlink-like path, or answer with a body that is not a zip at all.
 * These tests pin the behaviour for each of those, because the consequences (writing
 * outside the import directory, or reporting an empty repository as a success) are not
 * recoverable at the UI level.
 */
class RepoArchiveTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = folder.newFile("archive.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `unwraps the single top-level folder a host adds`() {
        val archive = zip("repo-main/index.html" to "<html/>", "repo-main/a/b.js" to "x")
        val target = File(folder.root, "out")

        val result = RepoArchive.extract(archive, target)

        assertThat(File(result, "index.html").readText()).isEqualTo("<html/>")
        assertThat(File(result, "a/b.js").readText()).isEqualTo("x")
    }

    @Test
    fun `keeps a tree that has no single top-level folder`() {
        val archive = zip("a.txt" to "a", "b.txt" to "b")
        val target = File(folder.root, "out")

        val result = RepoArchive.extract(archive, target)

        assertThat(File(result, "a.txt").readText()).isEqualTo("a")
        assertThat(File(result, "b.txt").readText()).isEqualTo("b")
    }

    @Test
    fun `an entry that escapes the target is skipped and nothing lands outside`() {
        val archive = zip("repo-main/ok.txt" to "ok", "../escaped.txt" to "no")
        val target = File(folder.root, "out")

        val result = RepoArchive.extract(archive, target)

        assertThat(File(result, "ok.txt").readText()).isEqualTo("ok")
        assertThat(File(folder.root, "escaped.txt").exists()).isFalse()
    }

    @Test
    fun `a requested sub-path is resolved inside the extracted tree`() {
        val archive = zip("repo-main/app/build.gradle" to "x")
        val target = File(folder.root, "out")

        val result = RepoArchive.extract(archive, target, subPath = "app")

        assertThat(result.name).isEqualTo("app")
        assertThat(File(result, "build.gradle").readText()).isEqualTo("x")
    }

    @Test
    fun `a sub-path that escapes is refused and the tree is removed`() {
        val archive = zip("repo-main/a.txt" to "a")
        val target = File(folder.root, "out")

        val failure = runCatching {
            RepoArchive.extract(archive, target, subPath = "../outside")
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a missing sub-path is refused`() {
        val archive = zip("repo-main/a.txt" to "a")
        val target = File(folder.root, "out")

        val failure = runCatching {
            RepoArchive.extract(archive, target, subPath = "missing")
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a body that is not a zip is an error, never an empty success`() {
        val notZip = folder.newFile("not.zip")
        notZip.writeText("this is not an archive")
        val target = File(folder.root, "out")

        val failure = runCatching { RepoArchive.extract(notZip, target) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(target.exists()).isFalse()
    }

    // ------------------------------------------------------------------- hosts

    @Test
    fun `a provider url becomes the recorded host`() {
        assertThat(RepoArchive.hostOf("https://github.com")).isEqualTo("github.com")
        assertThat(RepoArchive.hostOf("https://git.example.com/")).isEqualTo("git.example.com")
        assertThat(RepoArchive.hostOf("https://git.example.com:8443"))
            .isEqualTo("git.example.com:8443")
        assertThat(RepoArchive.hostOf("git.example.com")).isEqualTo("git.example.com")
        assertThat(RepoArchive.hostOf("")).isNull()
    }
}
