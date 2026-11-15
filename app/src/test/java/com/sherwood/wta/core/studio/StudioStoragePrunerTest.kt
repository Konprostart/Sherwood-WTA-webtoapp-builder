package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Storage housekeeping is only safe if it never deletes work a user might still need.
 * These tests pin both halves: the garbage goes, and anything that could be local work
 * stays.
 */
class StudioStoragePrunerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun file(dir: File, name: String, ageMs: Long, bytes: Int = 32): File {
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(now - ageMs)
        return f
    }

    private fun importCopy(
        root: File,
        name: String,
        owner: String = "acme",
        repo: String = "app",
        provider: String = "github",
        importedAgeMs: Long
    ): File {
        val dir = File(root, name)
        dir.mkdirs()
        RepoBridgeMetadataStore().write(
            dir,
            RepoBridgeMetadata(
                provider = provider,
                host = "github.com",
                owner = owner,
                repo = repo,
                ref = "main",
                baseSha = "a".repeat(40),
                importedAtEpochMs = now - importedAgeMs
            )
        )
        dir.setLastModified(now - importedAgeMs)
        return dir
    }

    @Test
    fun `stale temp files are deleted and recent ones are kept`() {
        val cache = temp.newFolder("cache")
        val stale = file(cache, "studio-artifact-1.tmp", day * 2)
        val recent = file(cache, "studio-artifact-2.tmp", 60_000)
        val unrelated = file(cache, "keep.log", day * 2)

        val report = StudioStoragePruner.pruneTempFiles(cache, now)

        assertThat(stale.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
        assertThat(report.tempFilesDeleted).isEqualTo(1)
        assertThat(report.bytesFreed).isEqualTo(32L)
    }

    @Test
    fun `vault partials are pruned only when no index record references them`() {
        val vault = temp.newFolder("vault")
        val orphan = file(vault, "app.apk.part", day * 2)
        val referenced = file(vault, "known.apk.part", day * 2)
        val staleImport = file(vault, ".import-42.tmp", day * 2)
        val index = file(vault, "index.json", day * 2)

        val report = StudioStoragePruner.pruneVaultPartials(
            vaultDir = vault,
            referencedNames = setOf("known.apk.part"),
            now = now
        )

        assertThat(orphan.exists()).isFalse()
        assertThat(staleImport.exists()).isFalse()
        assertThat(referenced.exists()).isTrue()
        assertThat(index.exists()).isTrue()
        assertThat(report.tempFilesDeleted).isEqualTo(2)
    }

    @Test
    fun `working copies without provenance are pruned only after the grace period`() {
        val root = temp.newFolder("git_imports")
        val abandoned = File(root, "abandoned").apply { mkdirs(); setLastModified(now - day * 3) }
        val inFlight = File(root, "in-flight").apply { mkdirs(); setLastModified(now - 60_000) }

        val report = StudioStoragePruner.pruneImports(
            roots = listOf(root),
            metadataStore = RepoBridgeMetadataStore(),
            now = now
        )

        assertThat(abandoned.exists()).isFalse()
        assertThat(inFlight.exists()).isTrue()
        assertThat(report.importsDeleted).isEqualTo(1)
    }

    @Test
    fun `copies beyond the per-repo cap are pruned once they are old`() {
        val root = temp.newFolder("git_imports")
        val copies = (1..5).map { i ->
            importCopy(root, "app-$i", importedAgeMs = day * 40L)
        }

        val report = StudioStoragePruner.pruneImports(
            roots = listOf(root),
            metadataStore = RepoBridgeMetadataStore(),
            now = now
        )

        // Newest three survive (all are equally old here, so ordering is by the
        // importer timestamp and then directory order); the two oldest are pruned.
        assertThat(report.importsDeleted).isEqualTo(2)
        assertThat(copies.count { it.exists() }).isEqualTo(3)
    }

    @Test
    fun `a copy with local edits is never pruned, even beyond the cap`() {
        val root = temp.newFolder("git_imports")
        // The edited copy is the oldest, so it is the first candidate for pruning.
        val edited = importCopy(root, "app-edited", importedAgeMs = day * 40L)
        File(edited, ".changes").mkdirs()
        File(edited, ".changes/note.txt").writeText("pre-edit snapshot")
        repeat(5) { i -> importCopy(root, "app-new-$i", importedAgeMs = day * 39L) }

        val report = StudioStoragePruner.pruneImports(
            roots = listOf(root),
            metadataStore = RepoBridgeMetadataStore(),
            now = now
        )

        assertThat(edited.exists()).isTrue()
        // Two beyond the cap go; the edited oldest copy does not.
        assertThat(report.importsDeleted).isEqualTo(2)
    }

    @Test
    fun `a recent working copy is never pruned`() {
        val root = temp.newFolder("git_imports")
        repeat(3) { i -> importCopy(root, "app-old-$i", importedAgeMs = day * 40L) }
        val recent = importCopy(root, "app-recent", importedAgeMs = day)

        val report = StudioStoragePruner.pruneImports(
            roots = listOf(root),
            metadataStore = RepoBridgeMetadataStore(),
            now = now
        )

        assertThat(recent.exists()).isTrue()
        assertThat(report.importsDeleted).isEqualTo(0)
    }

    @Test
    fun `the repository in use is never pruned`() {
        val root = temp.newFolder("git_imports")
        repeat(5) { i -> importCopy(root, "app-protected-$i", importedAgeMs = day * 40L) }
        val key = "github|acme/app"

        val report = StudioStoragePruner.pruneImports(
            roots = listOf(root),
            metadataStore = RepoBridgeMetadataStore(),
            now = now,
            protectedKeys = setOf(key)
        )

        assertThat(report.importsDeleted).isEqualTo(0)
        assertThat(root.listFiles()!!.count { it.isDirectory }).isEqualTo(5)
    }

    @Test
    fun `orphaned session sandboxes are pruned after the grace period`() {
        val sessions = temp.newFolder("sessions")
        val known = File(sessions, "known").apply { mkdirs(); setLastModified(now - day * 60) }
        val orphan = File(sessions, "orphan").apply { mkdirs(); setLastModified(now - day * 60) }
        val newOrphan = File(sessions, "new").apply { mkdirs(); setLastModified(now - 60_000) }

        val report = StudioStoragePruner.pruneOrphanSessions(
            sessionsRoot = sessions,
            knownSessionIds = setOf("known"),
            now = now
        )

        assertThat(known.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
        assertThat(newOrphan.exists()).isTrue()
        assertThat(report.sessionsDeleted).isEqualTo(1)
    }

    @Test
    fun `report aggregates without losing counts`() {
        val a = StorageReport(tempFilesDeleted = 1, bytesFreed = 10)
        val b = StorageReport(importsDeleted = 2, sessionsDeleted = 3, bytesFreed = 5, failures = 1)
        val sum = a + b
        assertThat(sum.tempFilesDeleted).isEqualTo(1)
        assertThat(sum.importsDeleted).isEqualTo(2)
        assertThat(sum.sessionsDeleted).isEqualTo(3)
        assertThat(sum.bytesFreed).isEqualTo(15L)
        assertThat(sum.failures).isEqualTo(1)
    }
}
